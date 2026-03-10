# Referência de Configuração — adapter.yaml

O n-gate é configurado através do arquivo `adapter.yaml`, cujo caminho é definido pela variável de ambiente `NGATE_CONFIG` (padrão: `config/adapter.yaml`).

---

## Estrutura Geral

```yaml
endpoints:
  default:
    listeners:     # Listeners HTTP/HTTPS
    backends:      # Backends de destino
    ruleMapping:   # Script Groovy global
    ruleMappingThreads: 1
    socketTimeout: 30

    # Jetty Thread Pool
    jettyMinThreads: 16
    jettyMaxThreads: 500
    jettyIdleTimeout: 120000

    # OkHttp Connection Pool
    connectionPoolSize: 256
    connectionPoolKeepAliveMinutes: 5

    # OkHttp Dispatcher
    dispatcherMaxRequests: 512
    dispatcherMaxRequestsPerHost: 256

# Blocos opcionais (nível raiz)
cluster:            # Cluster mode NGrid (opt-in)
admin:              # Admin API para deploy de rules
circuitBreaker:     # Circuit breaker por backend
rateLimiting:       # Rate limiting por listener/rota/backend
```

---

## Listeners

Cada listener é um servidor HTTP independente com porta, regras de segurança e backend default próprios.

```yaml
listeners:
  <nome-do-listener>:
    listenAddress: "0.0.0.0"       # Endereço de bind
    listenPort: 9090               # Porta TCP
    ssl: false                     # Habilita HTTPS
    scriptOnly: false              # Se true, apenas executa scripts (sem proxy)
    defaultScript: "Default.groovy" # Script padrão (legacy)
    defaultBackend: "keycloak"     # Backend de destino padrão
    secured: false                 # Habilita autenticação JWT
    secureProvider:                # Configuração do decoder JWT (se secured: true)
      providerClass: "..."
      name: "..."
      options: {}
    urlContexts:                   # Mapeamento de rotas
      <nome-contexto>:
        context: "/*"              # Path pattern
        method: "ANY"              # GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE, ANY
        ruleMapping: "..."         # Script Groovy para este contexto (sobrescreve global)
        secured: true              # Override de segurança por contexto
```

### Campos do Listener

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `listenAddress` | String | `"0.0.0.0"` | Endereço de bind da interface de rede |
| `listenPort` | Integer | — | Porta TCP do listener |
| `ssl` | Boolean | `false` | Habilita HTTPS (requer keystore em `ssl/`) |
| `scriptOnly` | Boolean | `false` | Se `true`, não faz proxy — apenas executa scripts e retorna respostas sintéticas |
| `defaultScript` | String | `"Default.groovy"` | Script Groovy executado por padrão (legacy) |
| `defaultBackend` | String | — | Nome do backend padrão (chave em `backends`) |
| `secured` | Boolean | `false` | Habilita validação JWT nos requests de entrada |
| `secureProvider` | Object | — | Configuração do provider de decodificação de token |
| `rateLimit` | Object | `null` | Referência a uma zona de rate limiting (veja [Rate Limiting](#rate-limiting)) |

### URL Contexts

Cada URL Context define um padrão de rota e seu comportamento:

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `context` | String | — | Path pattern (ex: `/*`, `/api/*`, `/health`) |
| `method` | String | — | Método HTTP ou `ANY` para todos |
| `ruleMapping` | String | — | Script Groovy específico (sobrescreve o global) |
| `secured` | Boolean | herda do listener | Override de segurança por rota |
| `rateLimit` | Object | `null` | Referência a uma zona de rate limiting por rota |

---

## Backends

Cada backend representa um serviço de destino para onde o n-gate encaminha requests.

```yaml
backends:
  <nome-do-backend>:
    backendName: "keycloak"
    xOriginalHost: null
    endPointUrl: "http://keycloak:8080"
    oauthClientConfig:             # Opcional: credenciais OAuth2
      ssoName: "inventory-keycloak"
      clientId: "ngate-client"
      clientSecret: "ngate-secret"
      userName: "inventory-svc"
      password: "inventory-svc-pass"
      tokenServerUrl: "http://keycloak:8080/realms/.../token"
      useRefreshToken: true
      renewBeforeSecs: 30
      authorizationServerUrl: null
      authScopes:
        - "openid"
        - "profile"
        - "email"
```

### Campos do Backend

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `backendName` | String | — | Identificador do backend |
| `xOriginalHost` | String | `null` | Header `X-Original-Host` a ser injetado |
| `endPointUrl` | String | — | URL base do backend (incluindo scheme e porta) |
| `oauthClientConfig` | Object | `null` | Se presente, habilita autenticação OAuth2 automática |
| `rateLimit` | Object | `null` | Referência a uma zona de rate limiting por backend |

### OAuth Client Config

Quando configurado, o n-gate obtém e injeta automaticamente um `Bearer` token no header `Authorization` de cada request ao backend.

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `ssoName` | String | — | Identificador único do SSO client |
| `clientId` | String | — | Client ID OAuth2 |
| `clientSecret` | String | — | Client Secret OAuth2 |
| `userName` | String | — | Usuário para grant `password` |
| `password` | String | — | Senha do usuário |
| `tokenServerUrl` | String | — | URL do endpoint de token |
| `useRefreshToken` | Boolean | `false` | Usa refresh token para renovação |
| `renewBeforeSecs` | Integer | `30` | Segundos antes da expiração para renovar |
| `authorizationServerUrl` | String | `null` | URL do authorization server (se diferente) |
| `authScopes` | List\<String\> | — | Scopes solicitados no token |

---

## Secure Provider

Configuração do decoder JWT para validação de tokens de entrada (quando `secured: true`).

```yaml
secureProvider:
  providerClass: "dev.nishisan.ngate.auth.jwt.JWTTokenDecoder"
  name: "local-keycloak-jwt"
  options:
    issuerUri: http://keycloak:8080/realms/inventory-dev
    jwkSetUri: http://keycloak:8080/realms/inventory-dev/protocol/openid-connect/certs
```

### Campos do Secure Provider

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `providerClass` | String | Classe Java do decoder (`JWTTokenDecoder`) ou path de script Groovy |
| `name` | String | Nome do provider (usado como cache key) |
| `options` | Map | Opções passadas ao decoder (ex: `issuerUri`, `jwkSetUri`) |

O `providerClass` pode ser:
- `dev.nishisan.ngate.auth.jwt.JWTTokenDecoder` — Decoder built-in
- Caminho de um script Groovy em `custom/` — Decoder customizado via closures

---

## Tuning de Performance

### Jetty Thread Pool

Controla o pool de threads do servidor HTTP (Jetty 12):

| Parâmetro | Tipo | Default | Descrição |
|-----------|------|---------|-----------|
| `jettyMinThreads` | Integer | `16` | Threads mínimas no pool |
| `jettyMaxThreads` | Integer | `500` | Threads máximas no pool |
| `jettyIdleTimeout` | Integer | `120000` | Timeout de idle (ms) para threads |

### OkHttp Connection Pool

Pool de conexões TCP compartilhado entre todos os backends:

| Parâmetro | Tipo | Default | Descrição |
|-----------|------|---------|-----------|
| `connectionPoolSize` | Integer | `256` | Conexões máximas no pool |
| `connectionPoolKeepAliveMinutes` | Integer | `5` | Tempo de keep-alive (minutos) |

### OkHttp Dispatcher

Controla concorrência de requests upstream (usa Virtual Threads):

| Parâmetro | Tipo | Default | Descrição |
|-----------|------|---------|-----------|
| `dispatcherMaxRequests` | Integer | `512` | Requests simultâneos máximos |
| `dispatcherMaxRequestsPerHost` | Integer | `256` | Requests simultâneos por host |

### Outros

| Parâmetro | Tipo | Default | Descrição |
|-----------|------|---------|-----------|
| `socketTimeout` | Integer | `30` | Timeout de socket (segundos) — aplica-se a connect, read, write e call |
| `ruleMapping` | String | `"default/Rules.groovy"` | Script Groovy global |
| `ruleMappingThreads` | Integer | `1` | Threads para execução de regras (ThreadPool) |

---

## Cluster Mode

O bloco `cluster:` habilita o cluster mode com NGrid. Se ausente ou `enabled: false`, o n-gate opera em modo standalone.

```yaml
cluster:
  enabled: true
  nodeId: "ngate-node1"              # Opcional: env NGATE_CLUSTER_NODE_ID → hostname → UUID
  host: "0.0.0.0"
  port: 7100
  clusterName: "ngate-cluster"
  seeds:
    - "ngate-node1:7100"
    - "ngate-node2:7100"
    - "ngate-node3:7100"
  replicationFactor: 2
  dataDirectory: "/tmp/ngrid-data"
```

### Campos do Cluster

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `enabled` | Boolean | `false` | Habilita o cluster mode NGrid |
| `nodeId` | String | `null` | ID do nó (fallback: env `NGATE_CLUSTER_NODE_ID` → hostname → UUID) |
| `host` | String | `"0.0.0.0"` | Endereço de bind do mesh TCP |
| `port` | Integer | `7100` | Porta do mesh TCP NGrid |
| `clusterName` | String | `"ngate-cluster"` | Nome do cluster (todos os nós devem usar o mesmo) |
| `seeds` | List\<String\> | `[]` | Lista de peers no formato `host:port` (inclui o próprio nó — self-seed é filtrado automaticamente) |
| `replicationFactor` | Integer | `2` | Fator de replicação dos dados no DistributedMap |
| `dataDirectory` | String | `"./data/ngrid"` | Diretório para persistência de dados NGrid |

---

## Admin API

O bloco `admin:` configura os endpoints administrativos para deploy de rules e consulta.

```yaml
admin:
  enabled: true
  apiKey: "my-secret-api-key"
```

### Campos do Admin

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `enabled` | Boolean | `false` | Habilita os endpoints `/admin/*` |
| `apiKey` | String | `null` | Chave de autenticação via header `X-API-Key` |

### Endpoints

| Endpoint | Método | Descrição |
|----------|--------|-----------|
| `/admin/rules/deploy` | POST | Upload multipart de scripts `.groovy` para deploy |
| `/admin/rules/version` | GET | Versão do bundle de rules ativo |

### Uso

```bash
# Deploy de rules (upload de todos os .groovy na pasta rules/)
curl -X POST http://localhost:9190/admin/rules/deploy \
  -H "X-API-Key: my-secret-api-key" \
  -F "scripts=@rules/default/Rules.groovy"

# Consultar versão do bundle ativo
curl http://localhost:9190/admin/rules/version \
  -H "X-API-Key: my-secret-api-key"
```

---

## Circuit Breaker

O bloco `circuitBreaker:` configura a proteção de backends com Resilience4j.

```yaml
circuitBreaker:
  enabled: true
  failureRateThreshold: 50
  waitDurationInOpenState: 60
  slidingWindowSize: 100
  permittedCallsInHalfOpenState: 10
  slowCallDurationThreshold: 5
  slowCallRateThreshold: 80
```

### Campos do Circuit Breaker

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `enabled` | Boolean | `false` | Habilita circuit breaker para todos os backends |
| `failureRateThreshold` | Integer | `50` | % de falhas para abrir o circuito |
| `waitDurationInOpenState` | Integer | `60` | Segundos em OPEN antes de transição para HALF_OPEN |
| `slidingWindowSize` | Integer | `100` | Tamanho da janela deslizante (requests) |
| `permittedCallsInHalfOpenState` | Integer | `10` | Requests permitidos em HALF_OPEN para teste |
| `slowCallDurationThreshold` | Integer | `5` | Segundos para considerar uma chamada lenta |
| `slowCallRateThreshold` | Integer | `80` | % de chamadas lentas para abrir o circuito |

### Comportamento

- **CLOSED:** Tráfego normal. Falhas são contabilizadas na sliding window.
- **OPEN:** Requests rejeitados com **HTTP 503** + header `x-circuit-breaker: OPEN`.
- **HALF_OPEN:** Número limitado de requests é permitido para testar recuperação.

---

## Rate Limiting

O bloco `rateLimiting:` configura controle de taxa de requests inspirado no `ngx_http_limit_req_module` do Nginx. Funciona em 3 escopos hierárquicos com dois modos de ação.

```yaml
rateLimiting:
  enabled: true
  defaultMode: "nowait"                # stall | nowait
  zones:
    api-global:
      limitForPeriod: 100              # requests por período
      limitRefreshPeriodSeconds: 1     # janela de refresh (1s = 100 req/s)
      timeoutSeconds: 5               # max espera em modo stall
    api-strict:
      limitForPeriod: 10
      limitRefreshPeriodSeconds: 1
      timeoutSeconds: 2
```

### Campos do Rate Limiting

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `enabled` | Boolean | `false` | Habilita rate limiting |
| `defaultMode` | String | `"nowait"` | Modo padrão: `stall` (aguarda slot) ou `nowait` (rejeita 429) |
| `zones` | Map | `{}` | Zonas nomeadas de rate limiting |

### Campos de Zona

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `limitForPeriod` | Integer | `100` | Requests permitidos por período |
| `limitRefreshPeriodSeconds` | Integer | `1` | Duração do período (segundos) |
| `timeoutSeconds` | Integer | `5` | Timeout de espera em modo stall (segundos) |

### Referência por Escopo (rateLimit)

O campo `rateLimit` pode ser adicionado em listeners, url contexts e backends:

```yaml
rateLimit:
  zone: "api-global"        # Nome da zona
  mode: "stall"             # Override do defaultMode (opcional)
```

| Campo | Tipo | Default | Descrição |
|-------|------|---------|-----------|
| `zone` | String | — | Nome da zona definida em `rateLimiting.zones` |
| `mode` | String | herda `defaultMode` | Override do modo: `stall` ou `nowait` |

### Modos de Operação

| Modo | Comportamento | HTTP Response |
|------|---------------|---------------|
| **nowait** | Rejeita imediatamente quando o limite é excedido | `429 Too Many Requests` |
| **stall** | Aguarda um slot livre até o timeout (bloqueia virtual thread) | `429` se timeout estourar |

### Hierarquia de Avaliação

A avaliação é cumulativa (não substitutiva):

1. **Listener** → checa rate limit do listener (inbound)
2. **Rota** → checa rate limit da rota (inbound)
3. **Backend** → checa rate limit do backend (outbound, pré-upstream)

Se qualquer nível rejeitar, o request é bloqueado com HTTP 429.

### Headers

| Header | Descrição |
|--------|-----------|
| `x-rate-limit` | `REJECTED` ou `DELAYED` |
| `x-rate-limit-zone` | Nome da zona |
| `x-rate-limit-scope` | Escopo: `route` ou `backend` |
| `Retry-After` | Segundos sugeridos de espera |

Para referência detalhada, veja [docs/rate-limiting.md](rate-limiting.md).

---

## Variáveis de Ambiente

| Variável | Default | Descrição |
|----------|---------|-----------|
| `NGATE_CONFIG` | `config/adapter.yaml` | Caminho do arquivo de configuração |
| `ZIPKIN_ENDPOINT` | — | URL do Zipkin collector (ex: `http://zipkin:9411/api/v2/spans`) |
| `TRACING_ENABLED` | `true` | Habilita/desabilita tracing |
| `SPRING_PROFILES_DEFAULT` | `dev` | Profile Spring Boot ativo (`dev`, `bench`) |
| `NGATE_CLUSTER_NODE_ID` | — | ID do nó do cluster (override do `nodeId` do YAML) |
| `NGATE_INSTANCE_ID` | hostname | ID da instância para tracing spans |
| `MANAGEMENT_PORT` | `9190` | Porta do Spring Boot Actuator (health, prometheus, admin API) |

---

## Exemplos

### Mínimo — Proxy simples sem autenticação

```yaml
---
endpoints:
  default:
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 8080
        ssl: false
        scriptOnly: false
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
        endPointUrl: "http://api-server:3000"

    ruleMapping: "default/Rules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 8
    jettyMaxThreads: 200
    jettyIdleTimeout: 60000
    connectionPoolSize: 64
    connectionPoolKeepAliveMinutes: 5
    dispatcherMaxRequests: 128
    dispatcherMaxRequestsPerHost: 64
```

### Avançado — Múltiplos listeners com autenticação

```yaml
---
endpoints:
  default:
    listeners:
      # Listener público (sem auth)
      public:
        listenAddress: "0.0.0.0"
        listenPort: 8080
        ssl: false
        scriptOnly: false
        defaultBackend: "public-api"
        secured: false
        urlContexts:
          all:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"

      # Listener privado (com auth JWT)
      private:
        listenAddress: "0.0.0.0"
        listenPort: 8443
        ssl: false
        scriptOnly: false
        defaultBackend: "private-api"
        secured: true
        secureProvider:
          providerClass: "dev.nishisan.ngate.auth.jwt.JWTTokenDecoder"
          name: "keycloak-jwt"
          options:
            issuerUri: http://keycloak:8080/realms/my-realm
            jwkSetUri: http://keycloak:8080/realms/my-realm/protocol/openid-connect/certs
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
      public-api:
        backendName: "public-api"
        endPointUrl: "http://public-service:3000"

      private-api:
        backendName: "private-api"
        endPointUrl: "https://private-service:8443"
        oauthClientConfig:
          ssoName: "private-sso"
          clientId: "gateway-client"
          clientSecret: "my-secret"
          userName: "svc-user"
          password: "svc-pass"
          tokenServerUrl: "http://keycloak:8080/realms/my-realm/protocol/openid-connect/token"
          useRefreshToken: true
          renewBeforeSecs: 30
          authScopes:
            - "openid"
            - "profile"

    ruleMapping: "default/Rules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 16
    jettyMaxThreads: 500
    jettyIdleTimeout: 120000
    connectionPoolSize: 256
    connectionPoolKeepAliveMinutes: 5
    dispatcherMaxRequests: 512
    dispatcherMaxRequestsPerHost: 256
```

### Cluster Mode — 3 nós com token sharing

```yaml
---
endpoints:
  default:
    listeners:
      http-noauth:
        listenAddress: "0.0.0.0"
        listenPort: 9091
        ssl: false
        scriptOnly: false
        defaultBackend: "static-backend"
        secured: false
        urlContexts:
          default:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"

    backends:
      static-backend:
        backendName: "static-backend"
        endPointUrl: "http://static-backend:8080"

    ruleMapping: "default/Rules.groovy"
    ruleMappingThreads: 1
    socketTimeout: 30
    jettyMinThreads: 16
    jettyMaxThreads: 500
    jettyIdleTimeout: 120000
    connectionPoolSize: 256
    connectionPoolKeepAliveMinutes: 5
    dispatcherMaxRequests: 512
    dispatcherMaxRequestsPerHost: 256

# nodeId é resolvido automaticamente via env NGATE_CLUSTER_NODE_ID
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
  dataDirectory: "/tmp/ngrid-data"

admin:
  enabled: true
  apiKey: "my-cluster-admin-key"

circuitBreaker:
  enabled: true
  failureRateThreshold: 50
  waitDurationInOpenState: 60
  slidingWindowSize: 100
```

> **Nota:** O `nodeId` é diferente para cada instância. Em Docker, use a variável de ambiente `NGATE_CLUSTER_NODE_ID` para definir o hostname do container. Veja `docker-compose.cluster.yml` para exemplo completo.
