# Upstream Pool com Priority Groups, Weighted Round-Robin e Active Health Check

## Contexto

O n-gate atualmente opera com um modelo 1:1 entre `backendName` e `endPointUrl`. Esta feature adiciona o conceito de **upstream pool** — múltiplos membros (servidores) agrupados sob um mesmo backend, com balanceamento de carga, prioridades e health check ativo.

O design é inspirado no upstream do NGINX, adaptado para a arquitetura Java 21 + OkHttp do n-gate.

## User Review Required

> [!CAUTION]
> **API BREAKER**: O campo `endPointUrl` é removido de `BackendConfiguration`. Todos os backends passam a usar obrigatoriamente a lista `members`. Documentação e testes serão atualizados.

> [!IMPORTANT]
> O Circuit Breaker passará a operar **por membro do pool** (não mais só por backendName), permitindo isolar um servidor com problemas sem afetar os demais membros.

---

## Proposed Changes

### Fase 1 — Modelo de Dados e Configuração YAML

#### [NEW] [UpstreamMemberConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/UpstreamMemberConfiguration.java)
- Campos: `url` (String), `priority` (int, default 1), `weight` (int, default 1), `enabled` (boolean, default true)
- Representa um membro individual do pool

#### [NEW] [UpstreamHealthCheckConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/UpstreamHealthCheckConfiguration.java)
- Campos: `enabled` (boolean), `path` (String, default "/health"), `intervalSeconds` (int, default 10), `timeoutMs` (int, default 3000), `unhealthyThreshold` (int, default 3), `healthyThreshold` (int, default 2)
- Configuração do probe ativo

#### [MODIFY] [BackendConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/BackendConfiguration.java)
- **Remover** campo `endPointUrl` (API breaker)
- Adicionar `List<UpstreamMemberConfiguration> members` (obrigatório)
- Adicionar `UpstreamHealthCheckConfiguration healthCheck`
- Adicionar `String strategy` (enum: `round-robin`, `failover`, `random`; default `round-robin`)

YAML resultante (exemplo):

```yaml
backends:
  # Backend simples (pool de 1 membro)
  backend-simples:
    backendName: "backend-simples"
    members:
      - url: "http://localhost:8080"

  # Backend com upstream pool completo
  api-pool:
    backendName: "api-pool"
    strategy: "round-robin"    # round-robin | failover | random
    healthCheck:
      enabled: true
      path: "/health"
      intervalSeconds: 10
      timeoutMs: 3000
      unhealthyThreshold: 3
      healthyThreshold: 2
    members:
      - url: "http://10.0.0.1:8080"
        priority: 1
        weight: 5
      - url: "http://10.0.0.2:8080"
        priority: 1
        weight: 3
      - url: "http://10.0.0.3:8080"
        priority: 2            # backup tier
        weight: 1
```

---

### Fase 2 — Upstream Pool Manager e Load Balancing

#### [NEW] [UpstreamMemberState.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/upstream/UpstreamMemberState.java)
- Wrapper que combina `UpstreamMemberConfiguration` com estado runtime: `healthy` (AtomicBoolean), `consecutiveFailures` (AtomicInteger), `consecutiveSuccesses` (AtomicInteger)

#### [NEW] [UpstreamPool.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/upstream/UpstreamPool.java)
- Gerencia `List<UpstreamMemberState>` agrupados por priority
- Método `selectMember()`:
  1. Filtra membros saudáveis (`healthy == true && enabled == true`)
  2. Seleciona o grupo de menor prioridade (tier) que tenha pelo menos 1 membro saudável
  3. Dentro do tier, aplica Weighted Round-Robin via `AtomicInteger` modular com expansão por peso
- Método `getAllMembers()` para iteração pelo health checker
- Thread-safe via concurrent data structures

#### [NEW] [UpstreamPoolManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/upstream/UpstreamPoolManager.java)
- Spring `@Component` que mantém `Map<String, UpstreamPool>` por backendName
- Método `initialize(Map<String, BackendConfiguration>)`: cria `UpstreamPool` para cada backend
- Método `getPool(String backendName)`: retorna o pool
- Método `selectMember(String backendName)`: retorna o `UpstreamMemberState` selecionado (ou `Optional.empty()` se todos down)

---

### Fase 3 — Active Health Check

#### [NEW] [UpstreamHealthChecker.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/upstream/UpstreamHealthChecker.java)
- `ScheduledExecutorService` com Virtual Threads (Java 21)
- Para cada pool com `healthCheck.enabled=true`:
  - Agenda um probe periódico (`intervalSeconds`)
  - Faz `GET {member.url}{healthCheck.path}` via OkHttp dedicado (timeout curto)
  - Resposta `2xx` = sucesso, qualquer outra = falha
  - Após `unhealthyThreshold` falhas consecutivas: marca `healthy=false`
  - Após `healthyThreshold` sucessos consecutivos: marca `healthy=true`
- Integração com lifecycle: inicia junto com o proxy, para no graceful shutdown
- Log estruturado de transições de estado (UP→DOWN, DOWN→UP)

---

### Fase 4 — Integração com HttpProxyManager

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/HttpProxyManager.java)
- Injetar `UpstreamPoolManager` no construtor
- No `handleRequest()` (L548-L586):
  - Após resolver o `backendName`, consultar `upstreamPoolManager.selectMember(backendName)`
  - Se o pool não tem membros saudáveis: retornar 503 (Service Unavailable)
  - Passar a URL do membro selecionado para `HttpRequestAdapter`
- Tags de tracing/métricas: adicionar `upstream.member.url` e `upstream.pool.strategy`

#### [MODIFY] [HttpRequestAdapter.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/HttpRequestAdapter.java)
- Alterar `getRequest()` para receber `String memberUrl` em vez de buscar `backendConfiguration.getEndPointUrl()`
- Assinatura: `getRequest(BackendConfiguration, String memberUrl, HttpWorkLoad, String)`

#### [MODIFY] [BackendCircuitBreakerManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/circuit/BackendCircuitBreakerManager.java)
- Quando o backend tem pool: criar CB por `backendName:memberUrl` em vez de apenas `backendName`
- Permite isolar membros individuais sem afetar o pool inteiro

---

### Fase 5 — Documentação

#### [NEW] [upstream-pool.md](file:///home/lucas/Projects/n-gate/docs/upstream-pool.md)
- Documentação em Markdown com exemplos de configuração YAML
- Referência ao diagrama PlantUML via proxy `uml.nishisan.dev`

#### [NEW] [upstream_pool.puml](file:///home/lucas/Projects/n-gate/docs/diagrams/upstream_pool.puml)
- Diagrama de sequência: request → pool manager → member selection → health check cycle
- Diagrama de componentes: relação entre BackendConfiguration, UpstreamPool, HealthChecker

---

## Verification Plan

### Testes Unitários (JUnit 5)

Novos testes que serão criados:

#### [NEW] [UpstreamPoolTest.java](file:///home/lucas/Projects/n-gate/src/test/java/dev/nishisan/ngate/upstream/UpstreamPoolTest.java)
- **T1:** Pool com 1 membro retorna sempre o mesmo membro
- **T2:** Weighted round-robin distribui requests proporcionalmente aos pesos
- **T3:** Membro marcado como unhealthy não é selecionado
- **T4:** Todos membros do tier 1 down → fallback para tier 2
- **T5:** Todos membros down → `selectMember()` retorna `Optional.empty()`
- **T6:** Membro com `enabled=false` nunca é selecionado

#### [NEW] [UpstreamHealthCheckerTest.java](file:///home/lucas/Projects/n-gate/src/test/java/dev/nishisan/ngate/upstream/UpstreamHealthCheckerTest.java)
- **T1:** Probe bem-sucedido mantém membro healthy
- **T2:** Após `unhealthyThreshold` falhas consecutivas → marca DOWN
- **T3:** Após `healthyThreshold` sucessos após DOWN → marca UP

#### [NEW] [BackendConfigurationDeserializationTest.java](file:///home/lucas/Projects/n-gate/src/test/java/dev/nishisan/ngate/upstream/BackendConfigurationDeserializationTest.java)
- **T1:** YAML com `members` deserializa corretamente para `BackendConfiguration` com pool
- **T2:** YAML com `healthCheck` preenche `UpstreamHealthCheckConfiguration`
- **T3:** YAML sem `members` resulta em erro de validação

**Comando para executar os testes:**
```bash
cd /home/lucas/Projects/n-gate && mvn test -pl . -Dtest="UpstreamPoolTest,UpstreamHealthCheckerTest,BackendConfigurationDeserializationTest" -DfailIfNoTests=false
```

### Verificação de Build

```bash
cd /home/lucas/Projects/n-gate && mvn clean compile -DskipTests
```

### Verificação Manual (se necessário)

1. Subir o n-gate com um `adapter.yaml` contendo um pool de 2 membros (um real + um inválido)
2. Verificar nos logs que o health checker marca o membro inválido como DOWN
3. Verificar que requests são roteados apenas para o membro saudável
4. Derrubar o membro saudável e verificar failover (ou 503 se todos caírem)
