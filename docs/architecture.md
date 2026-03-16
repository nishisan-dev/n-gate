# Arquitetura — n-gate

## Visão Geral

O n-gate é um API Gateway/Reverse Proxy construído sobre uma stack de alta performance:

- **Javalin 7** (Jetty 12) como servidor HTTP
- **OkHttp 4** como cliente HTTP para backends
- **Groovy 3** como motor de regras dinâmicas
- **Brave/Zipkin** para observabilidade distribuída
- **NGrid** (nishi-utils) para cluster mode com mesh TCP, leader election e DistributedMap
- **Resilience4j** para circuit breaker por backend
- **Rate Limiting** engine baseada em Semaphore com modos stall/nowait
- **Upstream Pool** com load balancing (round-robin/failover/random), priority groups e health checks ativos via Virtual Threads
- **Micrometer** para métricas Prometheus via Spring Boot Actuator
- **Spring Boot 3.5** para gerenciamento de configuração e ciclo de vida

O gateway recebe requests HTTP, os processa através de um pipeline configurável (autenticação → regras → roteamento → upstream → resposta) e retorna o resultado ao cliente.

---

## Diagrama de Arquitetura

![Arquitetura n-gate](https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/n-gate/main/docs/diagrams/architecture.puml)

---

## Componentes Principais

### 1. EndpointManager

- **Classe:** `dev.nishisan.ngate.manager.EndpointManager`
- **Responsabilidade:** Bootstrap do sistema. Lê a configuração (`adapter.yaml`), cria o `EndpointWrapper`, inicializa listeners e inicia os servidores Javalin.

### 2. EndpointWrapper

- **Classe:** `dev.nishisan.ngate.http.EndpointWrapper`
- **Responsabilidade:** Registra rotas HTTP no Javalin para cada listener configurado. Para cada request:
  1. Cria um root span de tracing (com extração B3)
  2. Adiciona tags HTTP semânticas
  3. Aplica rate limiting inbound (listener + rota)
  4. Verifica autenticação (se `secured: true`)
  5. Delega ao `HttpProxyManager`

### 3. HttpProxyManager

- **Classe:** `dev.nishisan.ngate.http.HttpProxyManager`
- **Responsabilidade:** Core do proxy. Gerencia o ciclo completo de um request:
  1. Avalia regras Groovy (`evalDynamicRules`)
  2. Resolve o backend de destino
  3. Aplica rate limiting outbound (backend)
  4. Seleciona membro upstream via `UpstreamPoolManager`
  5. Monta e executa o request upstream via OkHttp
  6. Processa a resposta via `HttpResponseAdapter`

### 4.5. UpstreamPoolManager

- **Classe:** `dev.nishisan.ngate.upstream.UpstreamPoolManager`
- **Responsabilidade:** Gerencia instâncias de `UpstreamPool` por backend. Ponto de entrada para seleção de membros upstream:
  - `selectMember(backendName)` — seleciona o próximo membro saudável via strategy do pool
  - `initialize(backends)` — cria pools a partir da configuração de backends
- **Dependências:** `UpstreamPool` (por backend), `UpstreamMemberState` (por membro), `UpstreamHealthChecker` (probing)
- **Documentação:** [upstream-pool.md](upstream-pool.md)

### 4. HttpWorkLoad

- **Classe:** `dev.nishisan.ngate.http.HttpWorkLoad`
- **Responsabilidade:** Objeto de contexto que carrega o estado de um request através do pipeline:
  - `request` — O request HTTP adaptado (pode ser modificado pelo Groovy)
  - `upstreamResponse` — A resposta recebida do backend
  - `clientResponse` — A resposta a ser enviada ao cliente
  - `responseProcessors` — Closures Groovy de pós-processamento
  - `returnPipe` — Flag de streaming (default: `true`)
  - `objects` — Map para compartilhar dados entre scripts e processors

### 5. Groovy Rules Engine

- **Classe:** `groovy.util.GroovyScriptEngine`
- **Responsabilidade:** Executa scripts `.groovy` do diretório `rules/`. Os scripts têm acesso ao `HttpWorkLoad` e podem:
  - Alterar roteamento (mudar backend, URI, headers)
  - Gerar respostas sintéticas
  - Registrar response processors
  - Chamar backends secundários
- **Hot-reload:** Recompilação automática a cada 60 segundos (configurável)

### 6. OAuthClientManager

- **Classe:** `dev.nishisan.ngate.auth.OAuthClientManager`
- **Responsabilidade:** Gerencia tokens OAuth2 para backends que requerem autenticação:
  - Cache de tokens com renovação automática
  - Suporte a refresh tokens
  - Renovação proativa (`renewBeforeSecs`)

### 7. Token Decoders

- **Interface:** `dev.nishisan.ngate.auth.ITokenDecoder`
- **Implementações:**
  - `JWTTokenDecoder` — Decodificação JWT built-in via JWKS
  - `CustomClosureDecoder` — Decodificação customizada via script Groovy
- **Responsabilidade:** Valida tokens JWT nos requests de entrada para endpoints secured

### 8. TracerService

- **Classe:** `dev.nishisan.ngate.observabitliy.service.TracerService`
- **Responsabilidade:** Gerencia instâncias de `Tracing` e `Tracer` (Brave). Cada listener tem seu próprio service name. O sender usa OkHttp para enviar spans ao Zipkin.

### 9. RateLimitManager

- **Classe:** `dev.nishisan.ngate.http.ratelimit.RateLimitManager`
- **Responsabilidade:** Engine de rate limiting baseada em `java.util.concurrent.Semaphore` com reset periódico:
  - Zonas nomeadas com N permits, resetadas por `ScheduledExecutorService`
  - **Modo nowait:** `tryAcquire()` sem timeout — rejeita imediatamente
  - **Modo stall:** `tryAcquire(timeout)` — bloqueia virtual thread
  - Métricas gauge via Micrometer (`ngate.ratelimit.available_permits`)
  - Configuração via bloco `rateLimiting:` do `adapter.yaml`

---

## Fluxo de um Request

![Fluxo de Request](https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/n-gate/main/docs/diagrams/request_flow.puml)

### Etapas

1. **Recepção** — Javalin recebe o request no listener configurado (porta 9090, 9091, etc.)
2. **Root Span** — `EndpointWrapper` cria o span raiz com extração B3 (se headers presentes)
3. **Tags Semânticas** — Método HTTP, URL, IP do cliente, User-Agent, etc.
4. **Rate Limit Inbound** — Verifica rate limiting no escopo listener e rota (HTTP 429 se rejeitado)
5. **Autenticação** (opcional) — Se `secured: true`, o `TokenDecoder` valida o JWT do header `Authorization`
6. **Regras Groovy** — `HttpProxyManager.evalDynamicRules()` executa os scripts do diretório `rules/`
7. **Resolução de Backend** — Backend definido pela regra ou pelo `defaultBackend` do listener
8. **Rate Limit Outbound** — Verifica rate limiting no escopo backend (HTTP 429 se rejeitado)
9. **Upstream Pool Selection** — `UpstreamPoolManager.selectMember(backendName)` seleciona um membro saudável do pool (strategy + priority groups). Se nenhum membro disponível → **503 Service Unavailable** com header `x-upstream-pool`
10. **Request Upstream** — OkHttp monta e executa o request para a URL do membro selecionado, com:
    - Injeção de headers B3 (tracing)
    - Injeção de `Authorization: Bearer` (se backend tem OAuth config)
    - Tag `upstream-member-url` no span de tracing
11. **Response Adapter** — `HttpResponseAdapter.writeResponse()` converte a resposta:
    - Executa response processors (closures Groovy)
    - Copia headers e status
    - Faz streaming (`returnPipe`) ou materialização
12. **Resposta ao Cliente** — Status, headers e body enviados. Header `x-trace-id` adicionado.
13. **Root Span Finalizado** — Tag `http.status_code` adicionada, span fechado.

---

## Modelo de Threading

```
┌───────────────────────────────────┐
│  Jetty 12 Thread Pool             │
│  Min: 16 / Max: 500 (configurável)│
│  Idle Timeout: 120s                │
│                                   │
│  Cada request = 1 thread Jetty    │
│  (request-per-thread model)       │
└───────────────────────────────────┘
            │
            ▼
┌───────────────────────────────────┐
│  OkHttp Dispatcher                │
│  Executor: Virtual Threads (J21)  │
│  Max Requests: 512 (configurável) │
│  Max Per Host: 256 (configurável) │
└───────────────────────────────────┘
            │
            ▼
┌───────────────────────────────────┐
│  OkHttp Connection Pool           │
│  Shared entre todos os backends   │
│  Size: 256 / Keep-Alive: 5min     │
│  (configurável via adapter.yaml)  │
└───────────────────────────────────┘
```

- **Jetty threads** gerenciam a recepção e o ciclo de vida do request HTTP
- **Virtual Threads** (Java 21) são usados no `Dispatcher` do OkHttp, permitindo milhares de requests upstream concorrentes sem bloqueio de OS threads
- **Connection Pool** é compartilhado entre todos os `OkHttpClient` instances, evitando TCP handshakes redundantes

---

## Streaming vs Materialização

O n-gate suporta dois modos de transferência de resposta:

### Streaming (`returnPipe = true`, padrão)

```
Backend InputStream ──buffer 8KB──▶ Client OutputStream
```

- Sem materialização em memória
- Ideal para large payloads (imagens, downloads, APIs com respostas grandes)
- Menor latência e uso de memória

### Materializado (`returnPipe = false`)

```
Backend InputStream ──▶ byte[] em memória ──▶ ctx.result(byte[])
```

- Body completo disponível para inspeção/transformação
- Necessário quando response processors precisam ler o body
- Maior uso de memória proporcional ao tamanho da resposta

O modo pode ser controlado via script Groovy:

```groovy
workload.returnPipe = false  // materializa para permitir inspeção
```

---

## Pacotes Java

| Pacote | Responsabilidade |
|--------|-----------------|
| `dev.nishisan.ngate` | Classe principal (`NGateApplication`) |
| `dev.nishisan.ngate.auth` | Autenticação: JWT, OAuth, Token Decoders |
| `dev.nishisan.ngate.auth.jwt` | Implementações JWT (built-in e closure) |
| `dev.nishisan.ngate.auth.wrapper` | Wrappers para tokens |
| `dev.nishisan.ngate.cluster` | Cluster mode: `ClusterService` (NGrid lifecycle, leadership, DistributedMap) |
| `dev.nishisan.ngate.configuration` | POJOs de configuração mapeados do `adapter.yaml` |
| `dev.nishisan.ngate.exception` | Exceções customizadas (SSO, Token) |
| `dev.nishisan.ngate.groovy` | `ProtectedBinding` — binding seguro para Groovy |
| `dev.nishisan.ngate.http` | Core: proxy, workload, adapters, context wrapper |
| `dev.nishisan.ngate.http.circuit` | `BackendCircuitBreakerManager` (Resilience4j) |
| `dev.nishisan.ngate.http.ratelimit` | `RateLimitManager`, `RateLimitResult` — engine de rate limiting |
| `dev.nishisan.ngate.http.clients` | Utilitários de HTTP client |
| `dev.nishisan.ngate.http.synth` | Respostas HTTP sintéticas |
| `dev.nishisan.ngate.manager` | Gerenciadores de configuração e endpoints |
| `dev.nishisan.ngate.upstream` | Upstream Pool: `UpstreamPoolManager`, `UpstreamPool`, `UpstreamMemberState`, `UpstreamHealthChecker` |
| `dev.nishisan.ngate.tunnel` | Tunnel Mode: `TunnelService`, `TunnelEngine`, `TunnelRegistry`, `VirtualPortGroup`, `BackendMember` |
| `dev.nishisan.ngate.tunnel.lb` | Algoritmos de LB: `RoundRobinBalancer`, `LeastConnectionsBalancer`, `WeightedRoundRobinBalancer` |
| `dev.nishisan.ngate.observabitliy` | TracerService, SpanWrapper, TracerWrapper, ProxyMetrics |

---

## Cluster Mode (NGrid)

O n-gate suporta um **cluster mode opt-in** que permite múltiplas instâncias coordenarem estado via mesh TCP. O cluster é alimentado pela biblioteca [NGrid](https://github.com/nishisan-dev/nishi-utils) (nishi-utils).

### Topologia

![Topologia Cluster](https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/n-gate/main/docs/diagrams/cluster_topology.puml)

Cada instância embarca um `NGridNode` que forma um mesh TCP com os demais nós. O mesh utiliza leader election baseada em quorum/epoch fencing e `DistributedMap` para replicação de estado.

### ClusterService

- **Classe:** `dev.nishisan.ngate.cluster.ClusterService`
- **Responsabilidade:** Gerencia o lifecycle do `NGridNode` embarcado. Inicializado via `@EventListener(ApplicationReadyEvent)`:
  1. Lê o bloco `cluster:` do `adapter.yaml`
  2. Se `enabled: false` (default), opera em modo standalone transparente
  3. Se `enabled: true`, cria e inicia o `NGridNode` com mesh TCP
  4. Registra listeners de leadership e gauges Micrometer
- **API para outros componentes:**
  - `isClusterMode()` — cluster ativo?
  - `isLeader()` — esta instância é líder? (`true` se standalone)
  - `getDistributedMap(name, keyType, valueType)` — obtém ou cria mapa distribuído
  - `addLeadershipListener(BiConsumer)` — notificação de mudança de líder

### Token Sharing (POW-RBL)

Tokens OAuth2 são compartilhados entre instâncias via `DistributedMap<String, SerializableTokenData>`:

1. **Publish-on-write:** Após obter ou renovar um token, a instância publica no `DistributedMap`
2. **Read-before-login:** Antes de ir ao IdP, cada instância verifica se existe token válido no mapa
3. **Resiliência:** Todas as instâncias mantêm `TokenRefreshThread` — se o mapa falhar, fazem login autônomo

### Rules Deploy e Replicação

O deploy de scripts Groovy é feito via Admin API (`POST /admin/rules/deploy`) e replicado no cluster:

1. O nó que recebe o deploy aplica localmente e publica o `RulesBundle` no `DistributedMap("ngate-rules")`
2. Followers detectam nova versão via polling thread (5s) e aplicam automaticamente
3. O `GroovyScriptEngine` é substituído via swap atômico (`volatile`)

---

## Tunnel Mode (TCP L4 Load Balancer)

O n-gate suporta um **tunnel mode** que funciona como TCP L4 load balancer, eliminando a necessidade de load balancers externos. O tunnel recebe conexões TCP e as distribui entre múltiplas instâncias proxy via algoritmos de balanceamento.

### Topologia

![Topologia Tunnel](https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/n-gate/main/docs/diagrams/tunnel_mode.puml)

### Princípio — Um processo é um OU outro

Um processo n-gate roda com `mode: proxy` OU `mode: tunnel`, nunca ambos. O tunnel se comporta como um listener TCP puro que distribui conexões entre proxies registrados.

### Componentes

| Componente | Classe | Responsabilidade |
|------------|--------|-----------------|
| **TunnelService** | `TunnelService` | Orquestrador Spring — inicializa Registry e Engine |
| **TunnelEngine** | `TunnelEngine` | Core TCP — `ServerSocketChannel` dinâmico + pipe bidirecional via Virtual Threads |
| **TunnelRegistry** | `TunnelRegistry` | Observa NGrid `DistributedMap` via polling — mantém `VirtualPortGroup`s sincronizados |
| **VirtualPortGroup** | `VirtualPortGroup` | Pool de membros para uma porta virtual — seleção via LB + promoção STANDBY |
| **TunnelRegistrationService** | `TunnelRegistrationService` | (modo proxy) Publica registro no NGrid + mantém keepalive |
| **TunnelMetrics** | `TunnelMetrics` | Instrumentação Prometheus completa |

### Registro (Push Model)

Cada proxy (`mode: proxy`) com `tunnel.registration.enabled: true` publica um `TunnelRegistryEntry` no NGrid `DistributedMap("ngate-tunnel-registry")`. O registro contém:
- `nodeId`, `host`, `listeners[]` (virtualPort → realPort), `status`, `weight`, `keepaliveInterval`

O proxy mantém keepalive periódico (default: 3s). No shutdown, entra em `DRAINING` e aguarda `drainTimeout` antes de remover o registro.

### Load Balancing

O tunnel suporta 3 algoritmos de load balancing:

| Algoritmo | Descrição |
|-----------|-----------|
| `round-robin` | Distribuição sequencial entre membros ativos |
| `least-connections` | Seleciona o membro com menor número de conexões ativas |
| `weighted-round-robin` | SWRR (Nginx-style) — distribuição proporcional aos pesos |

### Detecção de Falha

| Camada | Mecanismo | Ação |
|--------|-----------|------|
| **L1 — IOException** | `ConnectException`, `NoRouteToHostException` no connect | Remoção imediata do pool |
| **L2 — Keepalive timeout** | `missedKeepalives × keepaliveInterval` sem update | Remoção do pool |
| **Auto-promote** | Zero ACTIVE + STANDBY disponível | Promove STANDBY com maior peso |

### Boot Condicional

Quando `mode: tunnel`:
- `EndpointManager` **não** inicializa listeners Javalin
- `ConfigurationManager` **não** inicializa OAuth, Circuit Breaker, Rate Limiting
- `TunnelService` inicializa o `TunnelRegistry` e `TunnelEngine`

Para referência de configuração, veja [docs/configuration.md](configuration.md#tunnel-mode).

---

## Circuit Breaker

O n-gate protege backends com [Resilience4j](https://resilience4j.readme.io/), gerenciado pelo `BackendCircuitBreakerManager`.

- **Classe:** `dev.nishisan.ngate.http.circuit.BackendCircuitBreakerManager`
- **Ativação:** Bloco `circuitBreaker:` no `adapter.yaml` com `enabled: true`
- **Isolamento:** Um `CircuitBreaker` independente por backend (nome)
- **Transições:** `CLOSED → OPEN → HALF_OPEN → CLOSED`
- **Comportamento em OPEN:** Request rejeitado imediatamente com **HTTP 503** + header `x-circuit-breaker: OPEN`
- **Métricas:** Registradas automaticamente no Micrometer via `TaggedCircuitBreakerMetrics`

Para referência de configuração, veja [docs/configuration.md](configuration.md#circuit-breaker).

---

## Métricas Prometheus

O `ProxyMetrics` instrumenta o hot path com counters e timers Micrometer:

| Métrica | Tipo | Tags | Descrição |
|---------|------|------|-----------|
| `ngate.requests.total` | Counter | listener, method, status | Total de requests inbound |
| `ngate.request.duration` | Timer | listener, method | Latência inbound (ms) |
| `ngate.request.errors` | Counter | listener, method | Erros inbound |
| `ngate.upstream.requests` | Counter | backend, method, status | Total de requests upstream |
| `ngate.upstream.duration` | Timer | backend, method | Latência upstream (ms) |
| `ngate.upstream.errors` | Counter | backend, method | Erros upstream |
| `ngate.ratelimit.total` | Counter | scope, zone, result | Eventos de rate limiting (ALLOWED/REJECTED/DELAYED) |
| `ngate.ratelimit.available_permits` | Gauge | key | Permits disponíveis por rate limiter |
| `ngate.cluster.active.members` | Gauge | — | Membros ativos no cluster |
| `ngate.cluster.is.leader` | Gauge | — | 1=leader, 0=follower |
| `ngate.tunnel.connections.total` | Counter | vport, member | Conexões TCP aceitas no tunnel |
| `ngate.tunnel.connections.active` | Gauge | vport, member | Conexões TCP ativas |
| `ngate.tunnel.bytes.sent` | Counter | vport, member | Bytes enviados (client→backend) |
| `ngate.tunnel.bytes.received` | Counter | vport, member | Bytes recebidos (backend→client) |
| `ngate.tunnel.connect.errors` | Counter | vport, member, error | Erros de conexão ao backend |
| `ngate.tunnel.pool.members` | Gauge | vport, status | Membros por status no pool |

Endpoint: `GET /actuator/prometheus` (porta `9190`)


