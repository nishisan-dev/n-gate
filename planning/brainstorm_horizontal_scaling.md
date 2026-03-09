# Brainstorm: Escalabilidade Horizontal do n-gate

> **Status:** Brainstorm — ideias exploradas, nenhuma decisão final tomada.
> **Data:** 2026-03-08
> **Participantes:** Lucas + Antigravity

---

## 1. Diagnóstico da Arquitetura Atual

### 1.1 Estado In-Process (Pontos Críticos)

| Componente | Estado Local | Impacto na Escala |
|---|---|---|
| `OAuthClientManager.currentTokens` | `ConcurrentHashMap` de tokens OAuth | Cada instância faz login independente → multiplica carga no IdP |
| `HttpProxyManager.httpClients` | Pool de `OkHttpClient` por backend | ✅ OK — stateless, cada instância pode ter o seu |
| `HttpProxyManager.transientClients` | Cache Guava de clients temporários | ✅ OK — local por design |
| `EndpointWrapper.decoders` | Cache de `ITokenDecoder` (JWKs) | ⚠️ JWK rotation precisa ser detectada por cada instância |
| `GroovyScriptEngine` | Compilação de scripts do filesystem | ❌ Precisa de sync entre instâncias |

### 1.2 O Que Já Está Pronto para Scale-Out

- **Javalin handlers**: Stateless, sem sessão entre requests.
- **Virtual Threads (Loom)**: Javalin + OkHttp Dispatcher, altíssima concorrência por instância.
- **Tracing distribuído (B3)**: Brave/Zipkin já propaga contexto entre serviços.
- **Streaming architecture**: Zero-copy `returnPipe`, lazy body loading.

### 1.3 O Que Falta

- **Health check endpoint** (`/health`, `/ready`) para load balancers.
- **Graceful shutdown**: Não há drain de requests em andamento.
- **Configuração distribuída**: `adapter.yaml` é estático e local.
- **Coordenação entre instâncias**: Sem service discovery ou eleição de líder.

---

## 2. nishi-utils como Infraestrutura de Cluster

### 2.1 Cruzamento de Capacidades

A biblioteca `nishi-utils` (v3.1.0) fornece estruturas distribuídas que resolvem vários dos problemas identificados **sem dependências externas** (sem Redis, sem etcd, sem Zookeeper).

**Repositórios e Coordenadas Maven:**

| Projeto | Repositório | Maven `groupId:artifactId` | Versão |
|---|---|---|---|
| nishi-utils | [github.com/nishisan-dev/nishi-utils](https://github.com/nishisan-dev/nishi-utils) — local: `/home/lucas/Projects/nishisan/nishi-utils` | `dev.nishisan:nishi-utils` | 3.1.0 |
| nishi-utils-spring | [github.com/nishisan-dev/nishi-utils-spring](https://github.com/nishisan-dev/nishi-utils-spring) | `dev.nishisan:nishi-utils-spring` | 1.0.2 |

> **Nota:** Ambos publicados via GitHub Packages (`maven.pkg.github.com/nishisan-dev/*`).

| Problema | Componente nishi-utils | Avaliação |
|---|---|---|
| Token OAuth cache compartilhado | `DistributedMap<String, AuthToken>` | ✅ **Convergido** — líder faz login, replica para followers |
| Coordenação singleton (quem faz refresh) | `LeaderElectionUtils` / NGrid coordinator | ✅ **Perfeito** |
| Service discovery entre instâncias | `TcpTransport` (Gossip + Handshake) | ✅ **Perfeito** — autodiscovery via seed |
| Rules deploy atômico em cluster | `DistributedMap<String, RulesBundle>` | ✅ **Convergido** (ver seção 3) |
| ~~Cookie/session sync~~ | ~~`DistributedMap`~~ | ❌ **Descartado** — problema do backend, não do gateway |
| ~~Access log consolidado~~ | ~~`DistributedQueue`~~ | ❌ **Descartado** — syslog/journald resolve sem complexidade extra |
| Config compartilhada | `DistributedMap` | ✅ Potencialmente útil |

### 2.2 Topologia Proposta

```
                    ┌── n-gate-1 ──┐
                    │  NGridNode   │
Client → LB →      │  (leader)    │ → Backend(s)
                    ├── n-gate-2 ──┤
                    │  NGridNode   │
                    │  (follower)  │ → Backend(s)
                    ├── n-gate-3 ──┤
                    │  NGridNode   │
                    │  (follower)  │ → Backend(s)
                    └──────────────┘
                         ↕ TCP Mesh (Gossip + Replication)
```

Cada instância embarca um `NGridNode` e compartilha estado via replicação com quorum.

### 2.3 Decisões Descartadas

- **Log centralizado via NGrid:** Não é necessário. Logs serão tratados via mecanismos externos padrão (syslog, journald, ou shipper externo). Fora do escopo.
- **Cookie/session sync via NGrid:** Descartado. Se backends usam sessões, isso é responsabilidade do backend, não do gateway.

### 2.4 Riscos e Dúvidas Abertas

- **Footprint do NGrid dentro do n-gate**: Gossip + heartbeat + replicação impacta o hot path do proxy?
- **Longevidade operacional**: NGrid testado para meses de uptime sem leak?
- **Trade-off de complexidade**: NGrid in-process vs. Redis/etcd external — custo vs. benefício de ser self-contained.

---

## 3. Rules Deploy — Conceito Convergido ✅

### 3.1 Modelo Decidido

O hot-reload de scripts Groovy é **necessário**. A abordagem convergida é um **deploy explícito via CLI**, unificando standalone e cluster.

**Princípios:**
- A pasta `rules/` mantém a estrutura atual — sem mudanças.
- O `ngate-cli rules deploy` é o **único ponto de entrada** para aplicar regras.
- Funciona **igualmente** em standalone e cluster.
- Pre-parse (validação de sintaxe) acontece **no CLI**, antes do envio.

### 3.2 Fluxo

```
Operador edita scripts em rules/
        │
        ▼
ngate-cli rules deploy ./rules/ --target http://ngate:9090
        │
        ├── Lê .groovy recursivamente
        ├── Pre-parse (GroovyShell.parse) → valida sintaxe
        ├── Empacota como RulesBundle (version, timestamp, scripts)
        └── POST /admin/rules/deploy → n-gate
                │
                ▼
        ┌─ n-gate recebe bundle ──────────────────────┐
        │                                              │
        │  Standalone: aplica localmente               │
        │                                              │
        │  Cluster (leader):                           │
        │    DistributedMap.put("rules", bundle)        │
        │    → NGrid replica para followers            │
        │    → Followers aplicam automaticamente       │
        └──────────────────────────────────────────────┘
```

### 3.3 Artefato: `RulesBundle`

```java
public record RulesBundle(
    long version,
    Instant deployedAt,
    String deployedBy,
    Map<String, byte[]> scripts  // "default/Rules.groovy" → bytes
) implements Serializable {}
```

- Bundle atômico — todos os scripts ou nenhum.
- Footprint mínimo (< 1MB mesmo com dezenas de scripts).

### 3.4 Aplicação no Nó

- Materializa scripts em diretório temporário.
- Cria novo `GroovyScriptEngine`.
- Swap atômico via `volatile` reference.
- O `recompilationInterval` **perde o sentido** — o GSE roda fixo até o próximo deploy.

### 3.5 Boot Behavior

```
if (existeBundleDeployado()) {
    carregaDoBundlePersistido();  // último deploy via CLI
} else {
    carregaDoDisco("rules/");     // comportamento legado, primeiro boot
}
```

### 3.6 Componentes a Implementar

| Componente | Descrição |
|---|---|
| `RulesBundle` | Record serializable com version + scripts |
| `RulesBundleManager` | Recebe, valida, persiste e aplica bundles |
| Admin API endpoint | `POST /admin/rules/deploy`, `GET /admin/rules/version` |
| Admin API auth | Autenticação obrigatória (ver 3.7) |
| `ngate-cli` | Script/jar para deploy de rules |
| Integração NGrid | `DistributedMap` + listener para aplicação em followers |

### 3.7 Autenticação do Admin API ⚠️

O endpoint `/admin/*` e o `ngate-cli` **devem** ser autenticados. Opções avaliadas:

| Opção | Complexidade | Segurança | Dependência Externa |
|---|---|---|---|
| API Key (shared secret via `adapter.yaml`) | Baixa | Básica | Nenhuma |
| OAuth/JWT (via Keycloak, role `admin:rules`) | Média | Alta | Keycloak acessível |
| mTLS (certificado de cliente) | Alta | Muito Alta | PKI/certs |

**Pendência:** Definir qual abordagem adotar. API Key é suficiente para MVP.

---

## 4. Tokens OAuth — Ideia Explorada

### 4.1 Problema

O `OAuthClientManager` usa `ConcurrentHashMap` local. N instâncias = N logins independentes no IdP.

### 4.2 Abordagem Convergida ✅

- `DistributedMap<String, SerializableTokenData>` para compartilhar tokens.
- **Todas as instâncias** fazem login/refresh autonomamente (sem leader-only).
- **Publish-on-Write:** após obter/renovar token, publica no `DistributedMap`.
- **Read-before-Login:** antes de ir ao IdP, verifica se existe token válido no mapa.
- A `TokenRefreshThread` roda em **todas as instâncias** — resiliência total.
- Degrada gracefully para standalone se cluster falhar.

---

## 5. Infraestrutura — Itens Pendentes de Discussão

### 5.1 Admin Listener (Health + Métricas Exporter) ✅

- **Necessidade:** Obrigatório para LB, observabilidade operacional, e base para o `ngate-cli`.

#### Abordagem Convergida: nishi-utils-spring + Spring Boot Actuator

O projeto [`nishi-utils-spring`](https://github.com/nishisan-dev/nishi-utils-spring) (v1.0.2) já fornece um **bridge automático** entre `StatsUtils` e **Micrometer** (padrão do Spring Boot Actuator):

```
StatsUtils.notifyHitCounter("ngate.req.listener.http")
        ↓ (IStatsListener via StatsUtilsMetricBind)
MeterRegistry (Micrometer)
        ↓ (Spring Boot Actuator)
GET /actuator/prometheus  →  Prometheus/Grafana
GET /actuator/health      →  Load Balancer
```

**Componentes:**
- `NishisanStatsAutoConfiguration` — AutoConfig Spring Boot: detecta Micrometer no classpath e registra o bridge automaticamente.
- `StatsUtilsMetricBind` — Implementa `IStatsListener`: `Counter` para hits, `Gauge` para rates e averages.
- Ativação: `nishi.utils.stats.enabled=true` no `application.properties`.

**Benefícios:**
- Zero código de exporter custom — tudo via Actuator.
- Separação natural: Actuator rodando na porta do Spring Boot (management), Javalin nas portas de tráfego.
- Prometheus endpoint de graça: `/actuator/prometheus`.
- Health/readiness de graça: `/actuator/health` (customizável com `HealthIndicator`).

#### Endpoints Finais

| Endpoint | Porta | Propósito |
|---|---|---|
| `/actuator/health` | Spring Boot (management) | Liveness para LB |
| `/actuator/prometheus` | Spring Boot (management) | Scrape Prometheus/Grafana |
| `POST /admin/rules/deploy` | Javalin (admin) ou Spring | Deploy de rules via CLI |
| `GET /admin/rules/version` | Javalin (admin) ou Spring | Versão do bundle ativo |

#### Métricas a Instrumentar

**Per Listener (ingress):**
| Métrica | Tipo | Via |
|---|---|---|
| `ngate.requests.total{listener}` | Counter | `notifyHitCounter` |
| `ngate.request.duration.ms{listener}` | Average | `notifyAverageCounter` |
| `ngate.request.errors{listener}` | Counter | `notifyHitCounter` |

**Per Upstream (backend):**
| Métrica | Tipo | Via |
|---|---|---|
| `ngate.upstream.requests{backend}` | Counter | `notifyHitCounter` |
| `ngate.upstream.duration.ms{backend}` | Average | `notifyAverageCounter` |
| `ngate.upstream.status{backend,code}` | Counter | `notifyHitCounter` |

**Per Rule (hitcount):**
| Métrica | Tipo | Via |
|---|---|---|
| `ngate.rule.hits{script}` | Counter | `notifyHitCounter` |
| `ngate.rule.duration.ms{script}` | Average | `notifyAverageCounter` |

**Cluster (quando NGrid ativo):**
| Métrica | Tipo | Via |
|---|---|---|
| `ngate.cluster.active.members` | Gauge | `notifyCurrentValue` |
| `ngate.cluster.is.leader` | Gauge (0/1) | `notifyCurrentValue` |
| `ngate.cluster.replication.lag` | Gauge | `notifyCurrentValue` |
| `ngate.rules.bundle.version` | Gauge | `notifyCurrentValue` |

- **Esforço:** Baixo — adicionar dependência `nishi-utils-spring` + habilitar Actuator + instrumentar hot path com `StatsUtils`.

### 5.2 Graceful Shutdown

- **Necessidade:** Rolling updates sem request drops.
- **Proposta:** Shutdown hook → stop accepting requests → drain in-flight → close tracers → exit.
- **Esforço:** Médio.

### 5.3 Backend Overload Protection

- **Problema:** N instâncias × connectionPoolSize = muitas conexões no backend.
- **Proposta:** Circuit breaker (Resilience4j) ou limite global coordenado.
- **Esforço:** Médio/Alto.

### 5.4 Instance ID no Tracing

- **Problema:** Todas as instâncias reportam spans com o mesmo `localServiceName`. Impossível distinguir qual instância tratou um request.
- **Proposta:** Tag `instance-id` (hostname ou UUID) nas spans.
- **Esforço:** Baixo.

### 5.5 Configuração Distribuída

- **Problema:** `adapter.yaml` é estático.
- **Opções:** Config imutável via CI/CD, ou config centralizada via `DistributedMap`.
- **Status:** Não discutido em profundidade.

### 5.6 Target de Deploy

- **Dúvida:** Kubernetes, Docker Swarm, bare-metal com systemd?
- **Impacto:** Influencia estratégia de service discovery e configuração.
- **Status:** Não definido.

---

## 6. Mapa de Prioridades (Refinado)

### Fase 1 — Fundação (pré-requisitos para qualquer escala)

| # | Item | Prioridade | Esforço | Status | Notas |
|---|---|---|---|---|---|
| 1 | Métricas + Health (nishi-utils-spring + Actuator) | 🔴 Alta | Baixo | ✅ **Implementado** (Sessão 1) | Health Check via Actuator na porta `9190`. Métricas (Micrometer) pendentes para próxima sessão. |
| 2 | Graceful shutdown (drain + shutdown hook) | 🔴 Alta | Médio | ✅ **Implementado** (Sessão 1) | `@PreDestroy` + `Javalin.stop()` com drain nativo. |
| 3 | Instance ID no tracing | 🟢 Baixa | Muito Baixo | ✅ **Implementado** (Sessão 1) | `localServiceName` = `{service}@{instanceId}` nas spans Brave. |

### Fase 2 — Cluster Mode (habilita escala horizontal)

| # | Item | Prioridade | Esforço | Status | Dependência |
|---|---|---|---|---|---|
| 4 | NGrid integration (cluster mode) | 🔴 Alta | Alto | ✅ **Implementado** (Sessão 2) | Fase 1 completa |
| 5 | Token OAuth compartilhado (DistributedMap) | 🟡 Média | Médio | ✅ **Implementado** (Sessão 3) | Depende de #4. Publish-on-write + read-before-login (sem leader-only). |
| 6 | Rules Deploy (CLI + Bundle + replicação) | 🟡 Média | Médio | ✅ Convergido | Depende de #4 para cluster; funciona standalone sem |
| 7 | Auth do Admin API (API Key para MVP) | 🟡 Média | Baixo | Pendente | Necessário para #6 |

### Fase 3 — Resiliência & Observabilidade Avançada

| # | Item | Prioridade | Esforço | Status | Notas |
|---|---|---|---|---|---|
| 8 | Backend circuit breaker | 🟡 Média | Médio/Alto | Pendente | Proteção contra overload em N instâncias × pool. |
| 9 | Configuração distribuída | 🔵 Baixa | Médio | Pendente | CI/CD resolve no curto prazo. DistributedMap futuro. |

### Descartados

| Item | Motivo |
|---|---|
| Cookie/session sync via NGrid | Responsabilidade do backend, não do gateway. |
| Log centralizado via NGrid | Syslog/journald resolve sem complexidade extra. |

---

## 7. Registro de Sessões de Trabalho

### Sessão 1 — 2026-03-08 — `feature/horizontal-scaling-foundation`

**Escopo:** Fase 1 — Fundação (itens #1, #2, #3 do Mapa de Prioridades)

| Item | Descrição | Status |
|---|---|---|
| #1 | Health Check via Spring Boot Actuator (porta `9190`) | ✅ Concluído |
| #2 | Graceful Shutdown (`@PreDestroy` + Javalin `stop()`) | ✅ Concluído |
| #3 | Instance ID no Tracing (`localServiceName` = `{svc}@{id}`) | ✅ Concluído |

**Decisões da sessão:**
- Porta de management do Actuator: `9190` (via `MANAGEMENT_PORT` env)
- Instance ID: `NGATE_INSTANCE_ID` env → hostname → UUID random
- Métricas Prometheus (Micrometer/StatsUtils) ficaram para sessão dedicada

**Arquivos modificados/criados:**

| Arquivo | Alteração |
|---|---|
| `pom.xml` | Adicionado `spring-boot-starter-actuator` |
| `application.properties` | Porta management `9190`, endpoints `health,info` |
| `NGateHealthIndicator.java` | **[NEW]** HealthIndicator customizado (config + instanceId) |
| `TracerService.java` | Construtor com `resolveInstanceId()`, `localServiceName` qualificado |
| `EndpointWrapper.java` | Adicionado `stopAllListeners()` com drain Javalin 7 |
| `EndpointManager.java` | `@PreDestroy shutdown()`, lista `activeWrappers` |

**Commits:**

```
936a19f docs: add session tracking (seção 7) to horizontal scaling brainstorm
7d4c923 feat: add instance ID to tracing spans for multi-instance distinction
adb58f0 feat: add health check via Spring Boot Actuator
0109645 feat: add graceful shutdown for Javalin listeners
35aeec4 docs: update brainstorm session tracking — all Fase 1 items completed
```

**Build:** `mvn clean compile -DskipTests` ✅ (49 classes, 2.4s)

### Sessão 2 — 2026-03-08 — `feature/ngrid-cluster-mode`

**Escopo:** Fase 2 parcial — NGrid integration (item #4) e Token OAuth compartilhado (item #5)

| Item | Descrição | Status |
|---|---|---|
| #4 | NGrid integration — `ClusterService` embarca NGridNode, mesh TCP, leader election | ✅ Concluído |
| #5 | Token OAuth compartilhado — líder faz login/refresh, publica no `DistributedMap` | ⚠️ Parcial (revertido na Sessão 3) |

**Decisões da sessão:**
- Cluster mode é **opt-in** via bloco `cluster:` no `adapter.yaml`
- Sem `cluster:`, n-gate roda 100% standalone (zero impacto)
- `NGridNode` completo (não `LeaderElectionService` lightweight) — necessário para `DistributedMap`
- `TokenResponse` (Google) não é `Serializable` → criado `SerializableTokenData` record
- ~~`TokenRefreshThread` roda apenas no líder; followers leem do `DistributedMap`~~ (revertido na Sessão 3 — ver abaixo)
- Porta NGrid default: `7100` (env: `NGATE_CLUSTER_PORT`)
- Node ID: config → env `NGATE_CLUSTER_NODE_ID` → hostname → UUID random
- Rules Deploy (CLI + Bundle) ficou para sessão dedicada (escopo separado)

**Arquivos modificados/criados:**

| Arquivo | Alteração |
|---|---|
| `pom.xml` | Adicionado `dev.nishisan:nishi-utils:3.1.0` |
| `ClusterConfiguration.java` | **[NEW]** POJO Jackson para bloco `cluster:` do adapter.yaml |
| `ServerConfiguration.java` | Adicionado campo `ClusterConfiguration cluster` (nullable) |
| `ClusterService.java` | **[NEW]** Lifecycle do NGridNode, leadership listeners, DistributedMap |
| `SerializableTokenData.java` | **[NEW]** Record Serializable para transportar tokens via NGrid |
| `OAuthClientManager.java` | ~~Leader-only refresh + publish no DistributedMap~~ (revertido na Sessão 3 — causava deadlock) |
| `NGateHealthIndicator.java` | Cluster info: `clusterMode`, `isLeader`, `activeMembers`, `clusterNodeId` |
| `adapter.yaml` | Bloco `cluster:` comentado como exemplo |

**Build:** `mvn clean compile -DskipTests` ✅ (52 classes, 1.8s)

**Commits:**

```
fdddfe1 docs: add session 2 tracking and implementation plan for NGrid cluster mode
fad89dc feat: add cluster status to health check (isLeader, activeMembers)
4f6c580 feat: leader-only OAuth refresh with DistributedMap token sharing
51a1a30 feat: add ClusterService — NGridNode lifecycle, leadership and DistributedMap
67c1b7d feat: add cluster configuration support (opt-in via adapter.yaml)
d54474b feat: add nishi-utils 3.1.0 dependency for NGrid cluster integration
```

### Sessão 3 — 2026-03-08 — `feature/ngrid-cluster-mode` (fix regressão)

**Escopo:** Fix de regressão crítica — proxy não respondia a requests após mudanças da Sessão 2

| Item | Descrição | Status |
|---|---|---|
| Fix | Deadlock de inicialização Spring causado por dependência circular | ✅ Corrigido |
| #5 | Re-implementar token sharing com abordagem segura | ✅ Concluído |

**Diagnóstico:**
- O proxy aceitava TCP (Jetty) mas o handler Javalin **nunca era invocado**
- Thread dump: 12 Virtual Threads parqueadas em `Continuation.run` desde o startup
- Sem `BLOCKED`, sem deadlock explícito, sem conflito de classpath
- **Bisect** confirmou: `main` → HTTP 200 OK; `feature/ngrid-cluster-mode` → hang
- Isolamento final: apenas adicionar `@Autowired ClusterService` no `OAuthClientManager` causava o hang

**Causa raiz:**

Dependência circular no Spring Context:
```
OAuthClientManager →(@Autowired) ClusterService →(@Autowired) ConfigurationManager →(@Autowired) OAuthClientManager
```
Mesmo com `@Lazy`, o Spring não resolvia o ciclo corretamente durante a criação dos beans, causando um **deadlock silencioso** na inicialização — o Jetty subia, mas o Virtual Thread executor do Javalin nunca recebia o contexto pronto.

**Fix aplicado:**
- `OAuthClientManager`: removido `@Autowired ClusterService`, substituído por `applicationContext.getBean(ClusterService.class)` no `@EventListener(ApplicationReadyEvent.class)` — roda **após** todos os beans estarem criados
- `ClusterService`: removido `@Lazy` do `ConfigurationManager` (agora desnecessário)
- `OAuthClientManager` restaurado à lógica standalone original (100% idêntico à `main`), com apenas inicialização do `distributedTokenMap` adicionada

**Impacto no item #5:**
- O `DistributedMap` é **inicializado** corretamente no startup (quando cluster mode ativo)
- A lógica leader-only refresh + publish/read do DistributedMap **ainda não foi reimplementada** — precisa ser feita sem `synchronized` (incompatível com Virtual Threads) e sem `@Autowired` direto

**Arquivos modificados:**

| Arquivo | Alteração |
|---|---|
| `OAuthClientManager.java` | Restaurado ao original da `main` + lookup de `ClusterService` via `ApplicationContext` |
| `ClusterService.java` | Removido `@Lazy` do `ConfigurationManager` |
| `docker-compose.yml` | Mount do `settings.xml` para GitHub Packages |
| `docker-compose.bench.yml` | Flag `-s` para Maven settings |

**Commits:**

```
5515532 fix: resolve Docker build (GitHub Packages repo, settings mount, circular dep)
61b661c fix: add -s settings flag to bench compose for GitHub Packages auth
7b7583a fix: resolve deadlock de inicialização Spring — substituir @Autowired por ApplicationContext lookup
```

**Validação:** `curl http://localhost:9091/` → **HTTP 200 OK** ✅

**Lição aprendida:**
> Em projetos com Virtual Threads (Javalin/Loom) + Spring Boot, dependências circulares entre beans `@Service` causam deadlocks silenciosos — o servidor sobe mas nunca processa requests. Usar `ApplicationContext.getBean()` no `@EventListener(ApplicationReadyEvent)` é a forma segura de quebrar o ciclo.

**Continuação (item #5 — token sharing reimplementado):**

- **Abordagem:** Abandonado leader-only refresh (frágil, causou deadlock). Adotado **publish-on-write + read-before-login**.
- Todas as instâncias continuam refresh autônomo — resiliência total
- Após obter/renovar token → publica no `DistributedMap`
- Antes de ir ao IdP → verifica se existe token válido no mapa distribuído
- Degrada gracefully para standalone se cluster falhar

**Arquivos modificados (continuação):**

| Arquivo | Alteração |
|---|---|
| `OAuthClientManager.java` | Publish-on-write em `getAccessTokenFromPassword()` e `refreshToken()`, read-before-login em `getAccessToken()`, 3 helpers novos |

**Build:** `mvn clean compile -DskipTests` ✅ (52 classes, 2.3s)

---

### Sessão 4 — 9 de Março de 2026

**Itens implementados:** #6 (Rules Deploy) e #7 (Admin API Auth)

**Abordagem Rules Deploy:**
- `RulesBundle` — record Serializable (version, timestamp, deployedBy, scripts map)
- `RulesBundleManager` — @Service que gerencia lifecycle completo:
  - Recebe bundle via Admin API
  - Persiste em `data/rules-bundle.dat` (serialização Java)
  - Materializa scripts em tempdir
  - Cria novo `GroovyScriptEngine` e faz swap atômico (volatile)
  - Publica no `DistributedMap("ngate-rules")` para replicação cluster
  - Listener do DistributedMap para aplicar bundles de peers
  - Boot: carrega do bundle persistido, fallback para `rules/` dir
- `HttpProxyManager.gse` agora é `volatile` — swap thread-safe sem pause
- `EndpointWrapper` e `EndpointManager` expõem métodos para propagação do swap

**Abordagem Admin API Auth:**
- `AdminApiConfiguration` — POJO para bloco `admin:` do adapter.yaml (enabled, apiKey)
- `AdminController` — @RestController na porta management (9190/Actuator):
  - `POST /admin/rules/deploy` — multipart upload de .groovy scripts
  - `GET /admin/rules/version` — consulta versão do bundle ativo
  - Auth via header `X-API-Key` validado contra `admin.apiKey`

**Arquivos novos:**

| Arquivo | Descrição |
|---|---|
| `RulesBundle.java` | Record Serializable para bundle de scripts |
| `RulesBundleManager.java` | Lifecycle manager (deploy, persist, swap, replicate) |
| `AdminController.java` | REST controller com auth X-API-Key |
| `AdminApiConfiguration.java` | POJO para config admin |

**Arquivos modificados:**

| Arquivo | Alteração |
|---|---|
| `ServerConfiguration.java` | Campo `admin` adicionado |
| `HttpProxyManager.java` | `gse` volatile + `swapGroovyEngine()` |
| `EndpointWrapper.java` | Delegação `swapGroovyEngine()` |
| `EndpointManager.java` | `getActiveWrappers()` |
| `ConfigurationManager.java` | `getServerConfiguration()` |
| `adapter.yaml` | Bloco `admin:` (comentado) |

**Commits (7 atômicos):**

```
000db22 feat: add RulesBundle record for atomic Groovy script deployment
ca43d5b feat: add AdminApiConfiguration and integrate into ServerConfiguration
fb44c3f feat: volatile GSE field and swapGroovyEngine for hot rules deploy
5c22f39 feat: expose activeWrappers and serverConfiguration for admin/rules
42d7409 feat: add RulesBundleManager — deploy, persist, swap and replicate rules
0e5cb5b feat: add AdminController with X-API-Key auth for rules deploy
d59aef2 docs: add admin API block (commented) to adapter.yaml example
```

**Build:** Bloqueado por `nishi-utils:3.1.0` não disponível no cache Maven local (GitHub Packages sem auth, Nexus externo inacessível). Problema de infra, não de código. Validação pendente quando ambiente estiver configurado.

**Próximos passos:**
- Resolver dependência `nishi-utils:3.1.0` (publicar ou instalar localmente)
- Validar build `mvn clean compile -DskipTests`
- Implementar testes de integração T8 (standalone rules deploy) e T9 (cluster replication)
- Considerar backend circuit breaker (#8) e distributed configuration (#9)
