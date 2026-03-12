# n-gate — Product Overview

> API Gateway & Reverse Proxy programável de alta performance para ambientes Java 21, com motor de regras Groovy, upstream pools com active & passive health check, circuit breaker, rate limiting, CLI operacional, cluster mode e observabilidade nativa.

---

## Por que n-gate?

O n-gate nasceu da necessidade de um gateway HTTP que fosse **programável em runtime**, **observável por padrão** e **escalável horizontalmente** — sem sacrificar a simplicidade de configuração de um proxy reverso tradicional.

| Problema                        | Como o n-gate resolve                                                          |
|---------------------------------|--------------------------------------------------------------------------------|
| Roteamento estático             | Scripts Groovy com hot-reload decidem o destino em runtime                     |
| Backends frágeis                | Circuit breaker por backend + Upstream Pool com health check ativo             |
| Spike de tráfego                | Rate limiting granular (listener / rota / backend) com modo stall ou nowait    |
| Single point of failure         | Cluster mode NGrid com leader election, token sharing e rules deploy atômico   |
| Falta de visibilidade           | 13 spans semânticos por request (Zipkin/Brave) + métricas Prometheus           |
| Autenticação complexa           | JWT validation na entrada + OAuth2 token injection transparente no upstream    |
| Overhead de proxy               | Streaming zero-copy, Virtual Threads (Java 21) e connection pooling otimizado  |
| Operação de rules complexa      | CLI dedicado (`ngate-cli`) + Admin API REST para deploy, listagem e versionamento |

---

## Capacidades

### Proxy Reverso & Roteamento

O n-gate recebe requests HTTP e os encaminha para backends upstream. O roteamento é configurável em dois níveis:

- **Declarativo** — `defaultBackend` por listener via `adapter.yaml`
- **Programático** — scripts Groovy que podem alterar backend, headers, path e body em runtime

```yaml
# Roteamento declarativo: todo request vai para "my-api"
listeners:
  http:
    listenPort: 8080
    defaultBackend: "my-api"
```

```groovy
// Roteamento programático: decide em runtime
def path = context.path()

if (path.startsWith("/api/users"))
    upstreamRequest.setBackend("users-service")
else if (path.startsWith("/api/orders"))
    upstreamRequest.setBackend("orders-service")
```

---

### Upstream Pool — Load Balancing & High Availability

Cada backend pode ter **múltiplos servidores** organizados em **priority groups** com balanceamento de carga e health checks ativos.

```yaml
backends:
  my-api:
    backendName: "my-api"
    strategy: "round-robin"          # round-robin | failover | random
    members:
      - url: "http://server1:8080"
        priority: 1
        weight: 3                    # recebe 3x mais requests
      - url: "http://server2:8080"
        priority: 1
        weight: 1
      - url: "http://backup:8080"
        priority: 2                  # só usado se tier 1 inteiro cair
    healthCheck:
      enabled: true
      path: "/health"
      intervalSeconds: 10
      timeoutMs: 3000
      unhealthyThreshold: 3
      healthyThreshold: 2
```

**Estratégias disponíveis:**

| Estratégia    | Comportamento |
|---------------|---------------|
| `round-robin` | Weighted Round-Robin — distribui proporcionalmente ao `weight` |
| `failover`    | Sempre usa o primeiro membro disponível. Só muda quando cai |
| `random`      | Seleção aleatória ponderada pelo `weight` |

**Priority Groups:** Membros são agrupados por `priority`. O pool sempre usa o tier de menor valor. Só faz fallback quando **todos** os membros do tier atual estão DOWN.

**Active Health Check:** Probes periódicos via HTTP GET em Virtual Threads (Java 21). Transições baseadas em thresholds configuráveis. Se nenhum membro disponível → **503 Service Unavailable**.

#### Passive Health Check

Além do health check ativo (probes periódicos), o n-gate monitora as **respostas reais do tráfego de produção** para detectar membros degradados. Opera de forma independente do active health check.

```yaml
backends:
  my-api:
    passiveHealthCheck:
      enabled: true
      statusCodes:
        503:
          maxOccurrences: 4
          slidingWindowSeconds: 60
        502:
          maxOccurrences: 3
          slidingWindowSeconds: 30
        500:
          maxOccurrences: 10
          slidingWindowSeconds: 120
      recoverySeconds: 30
```

| Conceito | Comportamento |
|----------|---------------|
| **Sliding window** | Se `count(ocorrências no último slidingWindowSeconds) >= maxOccurrences`, membro marcado **DOWN** |
| **Por membro, por status** | Cada membro mantém janelas deslizantes independentes |
| **Recovery** | Após `recoverySeconds`, membro restaurado para tráfego de teste |
| **Coexistência** | Active e passive operam independentemente — qualquer um pode marcar DOWN |

---

### Circuit Breaker

Proteção por backend via [Resilience4j](https://resilience4j.readme.io/):

```yaml
circuitBreaker:
  enabled: true
  failureRateThreshold: 50          # % de falhas para abrir
  waitDurationInOpenState: 60       # segundos em OPEN
  slidingWindowSize: 100
  permittedCallsInHalfOpenState: 10
```

```
CLOSED ──(falhas > threshold)──▶ OPEN ──(wait)──▶ HALF_OPEN ──(sucesso)──▶ CLOSED
                                   │
                              HTTP 503
                       x-circuit-breaker: OPEN
```

---

### Rate Limiting

Controle de taxa inspirado no `ngx_http_limit_req_module` do Nginx, com zonas nomeadas aplicáveis em 3 escopos:

```yaml
rateLimiting:
  enabled: true
  defaultMode: "nowait"
  zones:
    api-global:
      limitForPeriod: 100            # 100 req/s
      limitRefreshPeriodSeconds: 1
      timeoutSeconds: 5
    api-strict:
      limitForPeriod: 10
      limitRefreshPeriodSeconds: 1
```

**Escopos de aplicação:**

```yaml
# Por listener (inbound)
listeners:
  http:
    rateLimit:
      zone: "api-global"

# Por rota (inbound)
urlContexts:
  api:
    rateLimit:
      zone: "api-strict"
      mode: "stall"                  # bloqueia virtual thread até slot

# Por backend (outbound)
backends:
  my-api:
    rateLimit:
      zone: "api-global"
      mode: "nowait"                 # rejeita imediatamente
```

| Modo       | Comportamento                              | Resposta HTTP |
|------------|--------------------------------------------|---------------|
| `nowait`   | Rejeita imediatamente                      | 429 + `Retry-After` |
| `stall`    | Aguarda slot (bloqueia virtual thread)     | 429 se timeout |

---

### Autenticação — JWT & OAuth2

Duas dimensões independentes:

**Inbound (Cliente → n-gate):** Validação JWT via JWKS com decoders built-in ou customizados (Groovy).

```yaml
listeners:
  api:
    secured: true
    secureProvider:
      providerClass: "dev.nishisan.ngate.auth.jwt.JWTTokenDecoder"
      name: "keycloak-jwt"
      options:
        issuerUri: http://keycloak:8080/realms/my-realm
        jwkSetUri: http://keycloak:8080/realms/my-realm/protocol/openid-connect/certs
```

**Outbound (n-gate → Backend):** Injeção automática de Bearer token via OAuth2 com cache, refresh e renovação proativa.

```yaml
backends:
  protected-api:
    members:
      - url: "https://api.internal:8443"
    oauthClientConfig:
      ssoName: "backend-sso"
      clientId: "gateway-client"
      clientSecret: "gateway-secret"
      tokenServerUrl: "http://keycloak:8080/realms/.../token"
      useRefreshToken: true
      renewBeforeSecs: 30
```

---

### Motor de Regras Groovy

Scripts Groovy executados no hot path com hot-reload automático (60s). Permitem:

- **Roteamento dinâmico** — alterar backend, path, headers
- **Respostas sintéticas** — mock/stub sem chamar backend
- **Response processors** — transformar a resposta antes de enviar ao cliente
- **Composição de APIs** — chamar múltiplos backends e compor resposta
- **Manipulação de cookies** — definir cookies via `Set-Cookie` em Response Processors
- **Redirecionamento** — respostas sintéticas com `Location` header
- **Controle de acesso** — validação de headers, bloqueio de IPs, abort com mensagem

```groovy
// Exemplo: routing + enrichment + resposta sintética condicional
def path = context.path()
def method = context.method().name()

if (path.startsWith("/api/v2/")) {
    upstreamRequest.setBackend("api-v2")
    upstreamRequest.addHeader("X-Gateway-Version", "n-gate")
} else if (path == "/api/status" && method == "GET") {
    def synth = workload.createSynthResponse()
    synth.setContent('{"status":"operational","version":"3.0"}')
    synth.setStatus(200)
    synth.addHeader("Content-Type", "application/json")
}
```

```groovy
// Exemplo: headers no response + cookies + segurança via Response Processor
def addResponseHeaders = { wl ->
    wl.clientResponse.addHeader("X-Powered-By", "n-gate")
    wl.clientResponse.addHeader("X-Content-Type-Options", "nosniff")
    wl.clientResponse.addHeader("Strict-Transport-Security", "max-age=31536000")
    wl.clientResponse.addHeader("Set-Cookie",
        "SESSION_ID=" + java.util.UUID.randomUUID().toString() + "; HttpOnly; Path=/")
}
workload.addResponseProcessor('addResponseHeaders', addResponseHeaders)
```

```groovy
// Exemplo: redirect condicional para nova versão da API
def path = context.path()
if (path.startsWith("/api/v1/")) {
    def synth = workload.createSynthResponse()
    synth.setStatus(301)
    synth.addHeader("Location", "https://api.example.com" + path.replace("/v1/", "/v2/"))
    synth.setContent('{"message": "Moved Permanently"}')
    synth.addHeader("Content-Type", "application/json")
}
```

Para a API completa e mais 12 exemplos práticos, veja [Regras Groovy](groovy_rules.md).

---

### Admin API & CLI

O n-gate expõe uma **Admin API REST** protegida por API Key para operações de gerenciamento de rules, e um **CLI dedicado** (`ngate-cli`) instalado via pacote `.deb`.

#### Endpoints

| Endpoint | Método | Descrição |
|----------|--------|-----------|
| `/admin/rules/deploy` | POST | Upload multipart de scripts `.groovy` — materializa em `rulesBasePath` |
| `/admin/rules/version` | GET | Versão do bundle ativo |
| `/admin/rules/list` | GET | Lista scripts do bundle com nome e tamanho |

#### CLI — `ngate-cli`

```bash
# Deploy de rules a partir de um diretório
ngate-cli deploy /etc/n-gate/rules

# Listar scripts do bundle ativo
ngate-cli list

# Consultar versão do bundle ativo
ngate-cli version
```

Configuração via variáveis de ambiente ou `/etc/n-gate/cli.conf`:

| Variável | Default | Descrição |
|----------|---------|-----------|
| `NGATE_ADMIN_URL` | `http://localhost:9190` | URL base da Admin API |
| `NGATE_API_KEY` | — | Chave de autenticação (obrigatória) |

**Rules Materialization:** Scripts deployados via Admin API são materializados em disco no diretório `rulesBasePath` (não mais em temp dirs), garantindo persistência e auditabilidade. Em cluster mode, o deploy é replicado automaticamente via `DistributedMap`.

---

### Cluster Mode (NGrid)

Múltiplas instâncias coordenadas via mesh TCP com:

- **Leader election** baseada em quorum/epoch fencing
- **Token sharing** (POW-RBL) — tokens OAuth2 compartilhados, evitando logins duplicados
- **Rules deploy atômico** — um nó recebe o deploy, replica via `DistributedMap`

```yaml
cluster:
  enabled: true
  host: "0.0.0.0"
  port: 7100
  clusterName: "ngate-cluster"
  seeds:
    - "ngate-node1:7100"
    - "ngate-node2:7100"
    - "ngate-node3:7100"
  replicationFactor: 2
```

```
┌──────────┐   ┌──────────┐   ┌──────────┐
│ n-gate-1 │◄─►│ n-gate-2 │◄─►│ n-gate-3 │
│  :9091   │   │  :9091   │   │  :9091   │
│  :7100   │   │  :7100   │   │  :7100   │
└────┬─────┘   └────┬─────┘   └────┬─────┘
     │   NGrid Mesh (TCP)          │
     └──────────┬──────────────────┘
                │
    DistributedMap: tokens + rules
```

---

### Observabilidade

#### Distributed Tracing (Brave/Zipkin)

Cada request gera um **trace completo** com **13 spans semânticos**:

```
rootSpan (SERVER)
├── request-handler
│   ├── dynamic-rules
│   │   └── rules-execution (por script)
│   ├── upstream-request (CLIENT)
│   └── response-adapter
│       ├── response-setup
│       ├── response-processor (por closure)
│       ├── response-headers-copy
│       └── response-send-to-client
└── token-decoder (se secured)
```

- **Propagação B3 bidirecional** — extrai contexto na entrada, injeta no upstream
- **Header `x-trace-id`** em toda resposta para correlação direta

#### Métricas Prometheus

Endpoint `/actuator/prometheus` (porta 9190) com:

| Métrica | Descrição |
|---------|-----------|
| `ngate.requests.total` | Requests inbound por listener/método/status |
| `ngate.request.duration` | Latência inbound (ms) |
| `ngate.upstream.requests` | Requests upstream por backend/método/status |
| `ngate.upstream.duration` | Latência upstream (ms) |
| `ngate.ratelimit.total` | Eventos de rate limiting |
| `ngate.cluster.active.members` | Membros ativos no cluster |
| `resilience4j.circuitbreaker.*` | Estado e métricas do circuit breaker |

---

## Cenários de Configuração

### Cenário 1 — Proxy Simples

O caso mais básico: proxy transparente para um único backend.

```yaml
---
endpoints:
  default:
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 8080
        defaultBackend: "my-api"
        secured: false
        urlContexts:
          default:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"

    backends:
      my-api:
        backendName: "my-api"
        members:
          - url: "http://api-server:3000"

    ruleMapping: "default/Rules.groovy"
    socketTimeout: 30
```

```bash
curl -i http://localhost:8080/api/resource
# → proxy transparente para http://api-server:3000/api/resource
# ← x-trace-id: a1b2c3d4e5f67890
```

---

### Cenário 2 — Load Balancing com Health Check

Dois servidores primários + um backup, com pesagem e probing ativo.

```yaml
backends:
  api:
    backendName: "api"
    strategy: "round-robin"
    members:
      - url: "http://api-1:8080"
        priority: 1
        weight: 3
      - url: "http://api-2:8080"
        priority: 1
        weight: 1
      - url: "http://api-dr:8080"
        priority: 2                    # disaster recovery
    healthCheck:
      enabled: true
      path: "/actuator/health"
      intervalSeconds: 5
      unhealthyThreshold: 2
      healthyThreshold: 1
```

**Comportamento:** `api-1` recebe ~75% do tráfego, `api-2` recebe ~25%. Se ambos caírem, failover automático para `api-dr`.

---

### Cenário 3 — Gateway API com Autenticação Full-Stack

JWT na entrada + OAuth2 no upstream + rate limiting + circuit breaker.

```yaml
---
endpoints:
  default:
    listeners:
      api:
        listenAddress: "0.0.0.0"
        listenPort: 9090
        defaultBackend: "core-api"
        secured: true
        secureProvider:
          providerClass: "dev.nishisan.ngate.auth.jwt.JWTTokenDecoder"
          name: "keycloak"
          options:
            issuerUri: http://keycloak:8080/realms/prod
            jwkSetUri: http://keycloak:8080/realms/prod/protocol/openid-connect/certs
        rateLimit:
          zone: "api-inbound"
        urlContexts:
          api:
            context: "/api/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"
            secured: true
          health:
            context: "/health"
            method: "GET"
            secured: false

    backends:
      core-api:
        backendName: "core-api"
        strategy: "round-robin"
        members:
          - url: "http://core-1:8080"
          - url: "http://core-2:8080"
        healthCheck:
          enabled: true
          path: "/health"
        oauthClientConfig:
          ssoName: "core-sso"
          clientId: "gateway"
          clientSecret: "secret"
          userName: "svc"
          password: "svc-pass"
          tokenServerUrl: "http://keycloak:8080/realms/prod/protocol/openid-connect/token"
          useRefreshToken: true
          renewBeforeSecs: 30
          authScopes: ["openid", "profile"]
        rateLimit:
          zone: "api-outbound"

    ruleMapping: "default/Rules.groovy"
    socketTimeout: 30
    jettyMinThreads: 16
    jettyMaxThreads: 500
    connectionPoolSize: 256
    dispatcherMaxRequests: 512
    dispatcherMaxRequestsPerHost: 256

rateLimiting:
  enabled: true
  defaultMode: "nowait"
  zones:
    api-inbound:
      limitForPeriod: 200
      limitRefreshPeriodSeconds: 1
    api-outbound:
      limitForPeriod: 100
      limitRefreshPeriodSeconds: 1

circuitBreaker:
  enabled: true
  failureRateThreshold: 50
  waitDurationInOpenState: 60
  slidingWindowSize: 100
```

---

### Cenário 4 — Multi-Backend com Roteamento Groovy

Um listener, múltiplos backends, roteamento decidido em runtime.

```yaml
backends:
  users:
    backendName: "users"
    members:
      - url: "http://users-svc:3001"

  products:
    backendName: "products"
    members:
      - url: "http://products-svc:3002"

  orders:
    backendName: "orders"
    strategy: "failover"
    members:
      - url: "http://orders-primary:3003"
        priority: 1
      - url: "http://orders-standby:3003"
        priority: 2
```

```groovy
// rules/default/Rules.groovy
def path = context.path()

if (path.startsWith("/api/users"))
    upstreamRequest.setBackend("users")
else if (path.startsWith("/api/products"))
    upstreamRequest.setBackend("products")
else if (path.startsWith("/api/orders"))
    upstreamRequest.setBackend("orders")
```

---

### Cenário 5 — Cluster Produção (3 nós)

Cluster NGrid com token sharing e load balancer externo.

```yaml
---
endpoints:
  default:
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 9091
        defaultBackend: "backend"
        secured: false
        urlContexts:
          default:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"

    backends:
      backend:
        backendName: "backend"
        strategy: "round-robin"
        members:
          - url: "http://backend-1:8080"
          - url: "http://backend-2:8080"
        healthCheck:
          enabled: true
          path: "/health"

    ruleMapping: "default/Rules.groovy"
    socketTimeout: 30
    jettyMinThreads: 16
    jettyMaxThreads: 500
    connectionPoolSize: 256
    dispatcherMaxRequests: 512
    dispatcherMaxRequestsPerHost: 256

cluster:
  enabled: true
  host: "0.0.0.0"
  port: 7100
  clusterName: "ngate-cluster"
  seeds:
    - "ngate-node1:7100"
    - "ngate-node2:7100"
    - "ngate-node3:7100"
  replicationFactor: 2
  dataDirectory: "/data/ngrid"

admin:
  enabled: true
  apiKey: "my-production-api-key"

circuitBreaker:
  enabled: true
  failureRateThreshold: 50
  waitDurationInOpenState: 60
  slidingWindowSize: 100
```

```bash
# Deploy de rules para todo o cluster (basta enviar para 1 nó)
curl -X POST http://ngate-node1:9190/admin/rules/deploy \
  -H "X-API-Key: my-production-api-key" \
  -F "scripts=@rules/default/Rules.groovy"
```

---

## Performance

O n-gate é otimizado para baixa latência no hot path:

| Característica | Detalhe |
|----------------|---------|
| **Threading** | Jetty 12 thread pool + OkHttp Dispatcher com Virtual Threads (Java 21) |
| **Streaming** | Modo `returnPipe` — transferência zero-copy InputStream → OutputStream (buffer 8KB) |
| **Connection Pool** | Pool compartilhado com keep-alive configurável, evitando TCP handshakes |
| **Groovy** | Recompilação a cada 60s, ~600µs por execução de script |
| **Async Logging** | Log4j2 + LMAX Disruptor — logging fora do hot path |
| **Health Check** | Virtual Threads dedicadas — não competem com threads de request |

---

## Tech Stack

| Componente | Função |
|------------|--------|
| **Java 21** | Runtime com Virtual Threads |
| **Spring Boot 3.5** | Configuração, ciclo de vida, Actuator |
| **Javalin 7** (Jetty 12) | HTTP Framework |
| **OkHttp 4** | HTTP Client para backends |
| **Groovy 3** | Motor de regras dinâmicas |
| **NGrid** (nishi-utils 3.2.0) | Cluster: mesh TCP, leader election, DistributedMap |
| **Resilience4j** | Circuit breaker |
| **Micrometer** | Métricas Prometheus |
| **Brave / Zipkin** | Distributed tracing |

---

## Documentação Detalhada

| Documento | Conteúdo |
|-----------|----------|
| [Arquitetura](architecture.md) | Componentes internos, modelo de threading, cluster mode |
| [Configuração](configuration.md) | Referência completa do `adapter.yaml`, Admin API, CLI |
| [Upstream Pool](upstream-pool.md) | Load balancing, priority groups, active & passive health checks |
| [Regras Groovy](groovy_rules.md) | API completa, 12 exemplos práticos, response processors |
| [Segurança](security.md) | JWT, OAuth2, policies por contexto, decoder customizado |
| [Observabilidade](observability.md) | Spans, tracing, métricas, circuit breaker |
| [Rate Limiting](rate-limiting.md) | Modos, zonas, configuração hierárquica |
| [Casos de Uso](use_cases.md) | 8 cenários end-to-end com configuração e validação |
| [Testes de Cluster](cluster_integration_tests.md) | Testes de integração Docker |
| [Desenvolvimento](development.md) | Build, testes unitários/integração, convenções e CI/CD |
