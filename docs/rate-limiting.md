# Rate Limiting

O n-gate suporta rate limiting granular inspirado no `ngx_http_limit_req_module` do Nginx. A feature permite controlar a taxa de requests em 3 escopos com dois modos de ação.

## Modos de Operação

| Modo | Comportamento | HTTP Response |
|------|------|------|
| **nowait** | Rejeita imediatamente quando o limite é excedido | `429 Too Many Requests` |
| **stall** | Aguarda um slot livre até o timeout configurado (bloqueia a virtual thread) | `429` se timeout estourar |

> O modo `stall` é equivalente ao `delay` do Nginx — requests são enfileirados até que um slot esteja disponível. Em modo `nowait`, o comportamento é idêntico ao `limit_req zone=... nodelay` do Nginx.

## Configuração

### Zonas

Zonas são definidas globalmente no bloco `rateLimiting:` do `adapter.yaml`:

```yaml
rateLimiting:
  enabled: true
  defaultMode: "nowait"           # modo padrão: stall | nowait
  zones:
    api-global:
      limitForPeriod: 100         # requests por período
      limitRefreshPeriodSeconds: 1  # período de refresh (100 req/s)
      timeoutSeconds: 5           # timeout para modo stall
    api-strict:
      limitForPeriod: 10
      limitRefreshPeriodSeconds: 1
      timeoutSeconds: 2
```

### Aplicação por Escopo

As zonas podem ser referenciadas em 3 escopos:

#### Listener

```yaml
listeners:
  http:
    rateLimit:
      zone: "api-global"
      mode: "stall"               # override do defaultMode
```

#### Rota (urlContext)

```yaml
urlContexts:
  payments:
    context: "/api/payments/*"
    method: "POST"
    rateLimit:
      zone: "api-strict"
      mode: "nowait"
```

#### Backend

```yaml
backends:
  keycloak:
    rateLimit:
      zone: "api-global"
      mode: "nowait"
```

## Hierarquia de Avaliação

A avaliação é **cumulativa** (não substitutiva):

1. **Listener** → checa rate limit do listener (inbound)
2. **Rota** → checa rate limit da rota específica (inbound)
3. **Backend** → checa rate limit do backend (outbound, pré-upstream)

Se qualquer nível rejeitar, o request é bloqueado naquele ponto com HTTP 429.

## Headers HTTP

| Header | Descrição |
|--------|-----------|
| `x-rate-limit: REJECTED` | Request rejeitado pelo rate limiter |
| `x-rate-limit: DELAYED` | Request foi atrasado (modo stall) |
| `x-rate-limit-zone` | Nome da zona que aplicou o rate limit |
| `x-rate-limit-scope` | Escopo: `route` ou `backend` |
| `Retry-After` | Tempo sugerido de espera (em segundos) |

## Métricas Prometheus

```
ngate_ratelimit_total{scope="listener",zone="api-global",result="ALLOWED"} 150
ngate_ratelimit_total{scope="listener",zone="api-global",result="REJECTED"} 5
ngate_ratelimit_total{scope="backend",zone="api-strict",result="DELAYED"} 12
```

Métricas nativas do Resilience4j RateLimiter também são expostas automaticamente via Micrometer.

## Diagrama de Fluxo

![Rate Limiting Flow](https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/n-gate/feature/rate-limit/docs/diagrams/rate_limiting.puml)
