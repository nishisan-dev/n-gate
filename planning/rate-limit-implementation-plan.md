# Rate Limiting — n-gate

Implementação de rate limiting granular no n-gate, inspirado no `ngx_http_limit_req_module` do Nginx. A feature permite controlar a taxa de requests por **listener**, **rota (urlContext)** e **backend**, com dois modos de ação: `stall` (aguarda slot — equivalente ao `delay` do Nginx) e `nowait` (rejeita imediatamente com HTTP 429).

## Proposta de Configuração YAML

A configuração segue o padrão hierárquico já existente no `adapter.yaml`, análogo ao circuit breaker.

```yaml
# --- Rate Limiting ---
# Define zonas de rate limiting e modo de ação.
# Pode ser aplicado em 3 níveis: listener, urlContext e backend.
# Modo: stall (aguarda slot como nginx delay) | nowait (429 imediato)

rateLimiting:
  enabled: true
  defaultMode: "nowait"               # stall | nowait
  zones:
    api-global:
      limitForPeriod: 100             # requests permitidos por período
      limitRefreshPeriodSeconds: 1    # janela de refresh (1s = 100 req/s)
      timeoutSeconds: 5              # max tempo de espera em modo stall
    api-strict:
      limitForPeriod: 10
      limitRefreshPeriodSeconds: 1
      timeoutSeconds: 2

# Exemplo de uso por listener:
endpoints:
  default:
    listeners:
      http:
        rateLimit:
          zone: "api-global"
          mode: "stall"               # override de modo para este listener
        urlContexts:
          payments:
            context: "/api/payments/*"
            method: "ANY"
            rateLimit:
              zone: "api-strict"
              mode: "nowait"          # override de modo para esta rota
    backends:
      keycloak:
        rateLimit:
          zone: "api-global"          # limita requests para este backend
          mode: "nowait"
```

### Hierarquia de Avaliação

A avaliação é **cumulativa** (não substituiva), seguindo a ordem:

1. **Listener** → checa rate limit do listener (inbound)
2. **Rota (urlContext)** → checa rate limit da rota específica (inbound)
3. **Backend** → checa rate limit do backend (outbound, antes do upstream call)

Se qualquer nível rejeitar, o request é bloqueado naquele ponto.

---

## Proposed Changes

### Dependência Maven

#### [MODIFY] [pom.xml](file:///home/lucas/Projects/n-gate/pom.xml)

Adicionar `resilience4j-ratelimiter` ao lado do `resilience4j-circuitbreaker` já existente:

```xml
<!-- Resilience4j: rate limiter for request throttling -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-ratelimiter</artifactId>
    <version>2.2.0</version>
</dependency>
```

---

### Configuração

#### [NEW] [RateLimitZoneConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/RateLimitZoneConfiguration.java)

Configuração de uma zona individual de rate limiting (equivalente a uma `limit_req_zone` do Nginx).

Campos:
- `limitForPeriod` (int, default 100) — requests permitidos por período
- `limitRefreshPeriodSeconds` (int, default 1) — duração do período de refresh
- `timeoutSeconds` (int, default 5) — tempo máximo de espera em modo stall

#### [NEW] [RateLimitConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/RateLimitConfiguration.java)

Configuração global do bloco `rateLimiting:` no adapter.yaml.

Campos:
- `enabled` (boolean, default false)
- `defaultMode` (String, default "nowait") — `stall` ou `nowait`
- `zones` (Map\<String, RateLimitZoneConfiguration\>) — zonas nomeadas

#### [NEW] [RateLimitRefConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/RateLimitRefConfiguration.java)

Referência a uma zona, usada nos 3 níveis (listener, rota, backend).

Campos:
- `zone` (String) — nome da zona definida em `rateLimiting.zones`
- `mode` (String, nullable) — override de modo (`stall`/`nowait`); se null, usa `defaultMode`

#### [MODIFY] [EndPointListenersConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/EndPointListenersConfiguration.java)

Adicionar campo `private RateLimitRefConfiguration rateLimit;` com getter/setter.

#### [MODIFY] [EndPointURLContext.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/EndPointURLContext.java)

Adicionar campo `private RateLimitRefConfiguration rateLimit;` com getter/setter.

#### [MODIFY] [BackendConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/BackendConfiguration.java)

Adicionar campo `private RateLimitRefConfiguration rateLimit;` com getter/setter.

#### [MODIFY] [ServerConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/ServerConfiguration.java)

Adicionar campo `private RateLimitConfiguration rateLimiting;` com getter/setter.

---

### Engine

#### [NEW] [RateLimitManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/ratelimit/RateLimitManager.java)

Manager centralizado que gerencia instâncias de `io.github.resilience4j.ratelimiter.RateLimiter` por chave composta (scope + nome).

**Responsabilidades:**
- `configure(RateLimitConfiguration config)` — inicializa zonas com base na configuração
- `acquirePermission(String scope, RateLimitRefConfiguration ref, String defaultMode)` — retorna `RateLimitResult` (ALLOWED, REJECTED, DELAYED)
- Internamente usa `RateLimiterRegistry` do Resilience4j
- Em modo `stall`: usa `rateLimiter.acquirePermission()` com timeout (bloqueia a virtual thread)
- Em modo `nowait`: usa `rateLimiter.acquirePermission(Duration.ZERO)` (rejeita imediatamente)
- Registra métricas Micrometer via `TaggedRateLimiterMetrics`

#### [NEW] [RateLimitResult.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/ratelimit/RateLimitResult.java)

Enum: `ALLOWED`, `REJECTED`, `DELAYED` — resultado da avaliação de rate limit.

---

### Integração no Pipeline

#### [MODIFY] [EndpointWrapper.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/EndpointWrapper.java)

No método `registerRoutes()`, **antes** de chamar `proxyManager.handleRequest()`, injetar a avaliação de rate limit para:

1. **Listener level** — `listenerConfig.getRateLimit()`
2. **Route level** — `urlContext.getRateLimit()`

Se `result == REJECTED`:
- Status: `429 Too Many Requests`
- Header: `x-rate-limit: REJECTED`
- Header: `x-rate-limit-zone: <nome da zona>`
- Header: `Retry-After: <timeoutSeconds da zona>`
- Span tag: `rate.limit=REJECTED`
- Return imediato (não processa request)

Se `result == DELAYED`:
- Header: `x-rate-limit: DELAYED`
- Span tag: `rate.limit=DELAYED`
- Continua processamento

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/HttpProxyManager.java)

No método `handleRequest()`, **antes** de executar o call upstream (mas após o Groovy rules), verificar rate limit do **backend** usando `backendConfiguration.getRateLimit()`.

Se rejeitado:
- Status: `429 Too Many Requests`
- Header: `x-rate-limit: REJECTED`
- Header: `x-rate-limit-scope: backend`
- Header: `x-upstream-id: <backendname>`
- Return imediato

#### [MODIFY] [EndpointManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/manager/EndpointManager.java)

- Injetar `RateLimitManager` via `@Autowired`
- Chamar `rateLimitManager.configure(serverConfig.getRateLimiting())` no `onStartup()`
- Passar `RateLimitManager` para `EndpointWrapper` e `HttpProxyManager` via construtor

---

### Métricas Prometheus

#### [MODIFY] [ProxyMetrics.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/observabitliy/ProxyMetrics.java)

Adicionar método `recordRateLimitEvent(String scope, String zone, String result)`:
- Counter: `ngate.ratelimit.total` com tags `scope`, `zone`, `result`
- Onde `scope` = listener/route/backend, `result` = ALLOWED/REJECTED/DELAYED

---

### Configuração de Exemplo

#### [MODIFY] [adapter.yaml](file:///home/lucas/Projects/n-gate/config/adapter.yaml)

Adicionar bloco comentado de exemplo do `rateLimiting:` no final do arquivo (seguindo o padrão do `circuitBreaker:`).

---

### Documentação

#### [NEW] [rate_limiting.md](file:///home/lucas/Projects/n-gate/docs/rate-limiting.md)

Documentação da feature cobrindo:
- Conceitos (zonas, modos stall vs nowait)
- Configuração YAML por nível
- Hierarquia de avaliação
- Métricas expostas
- Exemplos de uso

#### [NEW] [rate_limiting.puml](file:///home/lucas/Projects/n-gate/docs/diagrams/rate_limiting.puml)

Diagrama de sequência mostrando o fluxo de avaliação de rate limit através dos 3 níveis (listener → rota → backend).

---

## Verification Plan

### Compilação
```bash
cd /home/lucas/Projects/n-gate && mvn clean compile -DskipTests
```

### Testes Unitários

Criar `RateLimitManagerTest.java` em `src/test/java/dev/nishisan/ngate/ratelimit/`:

1. **Modo nowait** — envia requests acima do limite, verifica que retorna `REJECTED`
2. **Modo stall** — envia requests com delay configurado, verifica que retorna `DELAYED` e eventualmente `ALLOWED`
3. **Zonas independentes** — verifica que zonas diferentes não interferem entre si
4. **Disabled** — verifica que com `enabled=false` todos os requests passam

```bash
cd /home/lucas/Projects/n-gate && mvn test -Dtest="RateLimitManagerTest" -pl .
```

### Teste de Integração (Testcontainers)

Criar `RateLimitIntegrationTest.java` em `src/test/java/dev/nishisan/ngate/observability/` seguindo o padrão do `CircuitBreakerIntegrationTest.java`:

1. **T1: Nowait** — enviar burst > limite, verificar HTTP 429 + headers `x-rate-limit: REJECTED`
2. **T2: Stall** — verificar que requests dentro do timeout são atendidos com delay
3. **T3: Isolamento** — listener com rate limit não afeta listener sem rate limit
4. **T4: Métricas** — verificar contadores `ngate_ratelimit_total` no `/actuator/prometheus`

```bash
cd /home/lucas/Projects/n-gate && mvn test -Dtest="RateLimitIntegrationTest" -pl .
```

> [!IMPORTANT]
> Testes de integração requerem Docker para Testcontainers. O tempo de build da imagem pode ser significativo na primeira execução.
