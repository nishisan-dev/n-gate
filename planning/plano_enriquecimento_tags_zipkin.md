# Enriquecimento de Tags HTTP nos Spans Zipkin

O Zipkin atualmente exibe spans com informações mínimas (apenas `url-context` e `http-method`). O objetivo é adicionar tags HTTP semânticas seguindo as convenções OpenTelemetry/Zipkin, para que cada trace mostre dados úteis de request e response diretamente no Zipkin UI.

## Tags a Adicionar

As tags seguem a nomenclatura padrão `http.*` do OpenTelemetry/Zipkin:

| Tag | Descrição | Onde |
|-----|-----------|------|
| `http.method` | Método HTTP real da request | rootSpan (EndpointWrapper) |
| `http.url` | URL completa da request inbound | rootSpan |
| `http.path` | Path da request | rootSpan |
| `http.query` | Query string (se presente) | rootSpan |
| `http.status_code` | Status code da response final | rootSpan (no finally) |
| `http.client_ip` | IP do cliente | rootSpan |
| `http.user_agent` | User-Agent do cliente | rootSpan |
| `http.request.content_type` | Content-Type da request | rootSpan |
| `http.request.content_length` | Content-Length da request | rootSpan |
| `upstream.status_code` | Status code da resposta upstream | upstream-request span |
| `upstream.content_type` | Content-Type da resposta upstream | upstream-request span |

---

## Proposed Changes

### Camada HTTP — Spans de Request Inbound

#### [MODIFY] [EndpointWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/EndpointWrapper.java)

No handler de método específico (linha ~126–179) e no handler ANY (linha ~183–243), enriquecer o `rootSpan` com:

1. **Tags de request inbound** logo após a criação do `rootSpan`:
   - `http.method` → `ctx.method().name()` (método real, não o configurado)
   - `http.url` → `ctx.fullUrl()`
   - `http.path` → `ctx.path()`
   - `http.query` → `ctx.queryString()` (se não nulo)
   - `http.client_ip` → `ctx.ip()`
   - `http.user_agent` → `ctx.userAgent()` (se não nulo)
   - `http.request.content_type` → `ctx.contentType()` (se não nulo)
   - `http.request.content_length` → `ctx.contentLength()` (se > 0)

2. **Tag de response status** no bloco `finally` antes do `rootSpan.finish()`:
   - `http.status_code` → `ctx.statusCode()`

> [!NOTE]
> As tags `url-context` e `http-method` existentes serão mantidas por retrocompatibilidade, mas os novos campos `http.method`, `http.url` etc. seguem a convenção semântica padrão e são reconhecidos nativamente pelo Zipkin UI.

---

### Camada HTTP — Spans Upstream (Proxy)

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpProxyManager.java)

No span `upstream-request` (linha ~505–564), adicionar tags de resposta do backend:

1. Após receber a `Response res` (linha ~531), antes de chamar `handleResponse`:
   - `upstream.status_code` → `res.code()`
   - `upstream.content_type` → `res.header("Content-Type")` (se não nulo)
   - `upstream.content_length` → `res.header("Content-Length")` (se não nulo)

2. No span `request-handler` (linha ~417), adicionar tag do path real:
   - `http.path` → `handler.path()`

---

### Camada Observabilidade — SpanWrapper

#### [MODIFY] [SpanWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/observabitliy/wrappers/SpanWrapper.java)

Adicionar método de conveniência para tags com valor inteiro, evitando conversões `String.valueOf()` espalhadas:

```java
public Span tag(String k, int v) {
    return this.span.tag(k, String.valueOf(v));
}

public Span tag(String k, long v) {
    return this.span.tag(k, String.valueOf(v));
}
```

---

## Verificação

### Build Maven
```bash
cd /home/lucas/Projects/inventory-adapter && mvn clean package -DskipTests
```

### Manual — Zipkin UI
1. Rebuild e restart do container: `docker compose down inventory-adapter && docker compose up -d --build inventory-adapter`
2. Fazer uma request HTTP ao adapter (ex: `curl http://localhost:<porta>/algum-endpoint`)
3. Abrir Zipkin UI e verificar que o trace exibe as novas tags:
   - `http.status_code`, `http.url`, `http.path`, `http.method`, `http.client_ip`
   - No span upstream: `upstream.status_code`
