# Planejamento: Async Body Tee + Structured Access Log

> Features para posicionar o n-gate como ferramenta MITM de observabilidade.

---

## Feature 1: Async Body Tee

### Problema

Hoje o n-gate opera em dois modos mutuamente exclusivos:

- `returnPipe = true` — Streaming direto, mas **sem acesso ao body** (não materializa)
- `returnPipe = false` — Body em memória, **impactando latência e memória**

Para observabilidade MITM, precisamos de um terceiro modo: **capturar o body para inspeção/logging sem impactar o streaming do cliente**.

### Conceito

O **Tee** (inspirado no comando Unix `tee`) duplica o stream: um lado vai para o cliente (fast path), o outro vai para um buffer assíncrono (observação).

```
Backend InputStream
       │
       ├──▶ Cliente OutputStream (fast path, streaming)
       │
       └──▶ Async Buffer (captura para logging/inspeção)
```

### Projeto Técnico

#### 1. Nova classe: `TeeInputStream`

```
[NEW] src/main/java/dev/nishisan/ngate/http/tee/TeeInputStream.java
```

Wrapper sobre `InputStream` que, a cada `read()`, copia os bytes para um `ByteArrayOutputStream` interno (ou `PipedOutputStream` para processamento assíncrono).

```java
public class TeeInputStream extends InputStream {
    private final InputStream source;
    private final ByteArrayOutputStream capture;
    private final int maxCaptureBytes; // limite de captura (ex: 1MB)

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int bytesRead = source.read(b, off, len);
        if (bytesRead > 0 && capture.size() < maxCaptureBytes) {
            int toCapture = Math.min(bytesRead, maxCaptureBytes - capture.size());
            capture.write(b, off, toCapture);
        }
        return bytesRead;
    }

    public byte[] getCapturedBytes() {
        return capture.toByteArray();
    }

    public boolean wasTruncated() {
        return capture.size() >= maxCaptureBytes;
    }
}
```

#### 2. Nova classe: `BodyTeeHandler`

```
[NEW] src/main/java/dev/nishisan/ngate/http/tee/BodyTeeHandler.java
```

Gerencia o lifecycle do tee e despacha o body capturado para processamento assíncrono (fora do hot path):

```java
public class BodyTeeHandler {
    private final ExecutorService asyncExecutor; // Virtual Threads
    private final List<BodyCaptureSink> sinks;   // destinos da captura

    public TeeInputStream wrapStream(InputStream source, TeeConfig config) { ... }

    public void dispatchCapture(String traceId, String path, int status,
                                 byte[] requestBody, byte[] responseBody) {
        asyncExecutor.submit(() -> {
            sinks.forEach(sink -> sink.accept(traceId, path, status, requestBody, responseBody));
        });
    }
}
```

#### 3. Interface `BodyCaptureSink`

```
[NEW] src/main/java/dev/nishisan/ngate/http/tee/BodyCaptureSink.java
```

Permite múltiplos destinos para o body capturado:

```java
public interface BodyCaptureSink {
    void accept(String traceId, String path, int status,
                byte[] requestBody, byte[] responseBody);
}
```

Implementações possíveis (futuras):
- `LogBodyCaptureSink` — Loga o body truncado via Log4j2
- `FileBodyCaptureSink` — Salva em disco (por traceId)
- `WebhookBodyCaptureSink` — Envia para webhook externo

#### 4. Modificar `HttpResponseAdapter.writeResponse()`

```
[MODIFY] src/main/java/dev/nishisan/ngate/http/HttpResponseAdapter.java
```

Na **Fase 5** (streaming pipe), envolver o `InputStream` do upstream com o `TeeInputStream`:

```java
// Antes (atual):
try (InputStream inputStream = w.getUpstreamResponse().getOkHttpResponse().body().byteStream()) { ... }

// Depois:
InputStream rawStream = w.getUpstreamResponse().getOkHttpResponse().body().byteStream();
TeeInputStream teeStream = bodyTeeHandler.wrapStream(rawStream, teeConfig);
try (InputStream inputStream = teeStream) {
    // ... streaming normal para o cliente ...
}
// Async: despacha body capturado (fora do hot path)
bodyTeeHandler.dispatchCapture(traceId, path, status,
    requestBodyBytes, teeStream.getCapturedBytes());
```

#### 5. Configuração no `adapter.yaml`

```yaml
endpoints:
  default:
    # ... configuração existente ...

    bodyTee:
      enabled: false              # default: desabilitado (zero overhead)
      maxCaptureBytes: 1048576    # 1MB — trunca após este limite
      captureRequest: true        # captura body do request
      captureResponse: true       # captura body do response
      contentTypeFilter:          # captura apenas estes content-types
        - "application/json"
        - "application/xml"
        - "text/*"
      excludePaths:               # paths ignorados (health, metrics)
        - "/health"
        - "/metrics"
      sink: "log"                 # "log", "file", "webhook"
      sinkConfig:
        webhookUrl: "http://..."  # se sink = webhook
        fileDir: "/var/log/ngate" # se sink = file
```

#### 6. Nova classe de configuração

```
[NEW] src/main/java/dev/nishisan/ngate/configuration/BodyTeeConfiguration.java
```

POJO mapeado do YAML:

```java
public class BodyTeeConfiguration {
    private Boolean enabled = false;
    private Integer maxCaptureBytes = 1_048_576;
    private Boolean captureRequest = true;
    private Boolean captureResponse = true;
    private List<String> contentTypeFilter;
    private List<String> excludePaths;
    private String sink = "log";
    private Map<String, String> sinkConfig;
}
```

### Impacto de Performance

| cenário | overhead |
|---------|----------|
| `bodyTee.enabled = false` | **Zero** — nenhum código extra no hot path |
| `bodyTee.enabled = true` | ~memcpy do buffer (8KB chunks) + async dispatch |
| Sem content-type match | Skip imediato (checagem de header apenas) |

O dispatch do body capturado é sempre **assíncrono** (Virtual Thread), garantindo que o cliente receba a resposta **antes** do processamento do body.

---

## Feature 2: Structured Access Log

### Problema

Hoje os logs do n-gate são textuais (Log4j2), dificultando análise automatizada, correlação e ingestão por ferramentas como Elasticsearch, Loki ou CloudWatch.

### Conceito

Um **access log estruturado em JSON** emitido ao final de cada request, contendo todos os metadados relevantes em campos tipados — independente do log tradicional.

### Projeto Técnico

#### 1. Nova classe: `AccessLogEntry`

```
[NEW] src/main/java/dev/nishisan/ngate/logging/AccessLogEntry.java
```

Record (Java 21) com todos os campos do access log:

```java
public record AccessLogEntry(
    // Identificação
    String traceId,
    String listener,
    String contextName,

    // Request
    String method,
    String path,
    String queryString,
    String clientIp,
    String userAgent,
    String contentType,
    long requestContentLength,

    // Upstream
    String backend,
    String upstreamUrl,
    int upstreamStatus,
    String upstreamContentType,
    long upstreamContentLength,

    // Response
    int status,
    boolean returnPipe,
    boolean synthetic,

    // Timing (milissegundos)
    long totalDuration,
    long rulesDuration,
    long upstreamDuration,
    long responseDuration,

    // Auth
    String userId,          // do JWT, se disponível
    boolean authenticated,

    // Metadata
    String timestamp,       // ISO-8601
    String gatewayVersion
) {}
```

#### 2. Nova classe: `AccessLogEmitter`

```
[NEW] src/main/java/dev/nishisan/ngate/logging/AccessLogEmitter.java
```

Responsável por serializar e emitir o `AccessLogEntry`:

```java
public class AccessLogEmitter {
    private static final Logger ACCESS_LOG = LogManager.getLogger("ngate.access");
    private final Gson gson;

    public void emit(AccessLogEntry entry) {
        ACCESS_LOG.info(gson.toJson(entry));
    }
}
```

Usa um **logger dedicado** (`ngate.access`) configurado com appender próprio no Log4j2, separado do log principal:

```xml
<!-- log4j2.xml — novo appender para access log -->
<Logger name="ngate.access" level="INFO" additivity="false">
    <AppenderRef ref="AccessLogFile"/>
</Logger>

<RollingFile name="AccessLogFile"
    fileName="log/access.log"
    filePattern="log/access-%d{yyyy-MM-dd}.log.gz">
    <PatternLayout pattern="%m%n"/>  <!-- JSON puro, sem prefixo -->
    <Policies>
        <TimeBasedTriggeringPolicy interval="1"/>
    </Policies>
</RollingFile>
```

#### 3. Coleta de timings: `RequestTimings`

```
[NEW] src/main/java/dev/nishisan/ngate/logging/RequestTimings.java
```

Objeto que acumula durações durante o pipeline:

```java
public class RequestTimings {
    private long startNanos;
    private long rulesStartNanos, rulesEndNanos;
    private long upstreamStartNanos, upstreamEndNanos;
    private long responseStartNanos, responseEndNanos;

    public long totalMs()    { return (responseEndNanos - startNanos) / 1_000_000; }
    public long rulesMs()    { return (rulesEndNanos - rulesStartNanos) / 1_000_000; }
    public long upstreamMs() { return (upstreamEndNanos - upstreamStartNanos) / 1_000_000; }
    public long responseMs() { return (responseEndNanos - responseStartNanos) / 1_000_000; }
}
```

#### 4. Modificações no pipeline

```
[MODIFY] src/main/java/dev/nishisan/ngate/http/HttpWorkLoad.java
```

Adicionar campo `RequestTimings` ao `HttpWorkLoad`:

```java
private final RequestTimings timings = new RequestTimings();
public RequestTimings getTimings() { return timings; }
```

```
[MODIFY] src/main/java/dev/nishisan/ngate/http/HttpProxyManager.java
```

Instrumentar `evalDynamicRules()` e `handleRequest()` para registrar timings:

```java
// em evalDynamicRules()
workLoad.getTimings().markRulesStart();
// ... execução ...
workLoad.getTimings().markRulesEnd();

// em handleRequest() — upstream
workLoad.getTimings().markUpstreamStart();
Response res = this.getHttpClientByListenerName(backendname).newCall(req).execute();
workLoad.getTimings().markUpstreamEnd();
```

```
[MODIFY] src/main/java/dev/nishisan/ngate/http/EndpointWrapper.java
```

No bloco `finally` do handler (onde `rootSpan.finish()` é chamado), emitir o access log:

```java
} finally {
    rootSpan.tag("http.status_code", ctx.statusCode());
    ctx.header("x-trace-id", traceWrapper.getTraceId());
    rootSpan.finish();

    // Access Log (async)
    accessLogEmitter.emit(AccessLogEntry.from(customCtx, w, traceWrapper));
}
```

#### 5. Configuração no `adapter.yaml`

```yaml
endpoints:
  default:
    # ... configuração existente ...

    accessLog:
      enabled: true               # default: habilitado
      format: "json"              # "json" ou "combined" (Apache-style)
      loggerName: "ngate.access"  # Logger Log4j2 dedicado
      includeHeaders: false       # logar headers do request (cuidado com PII)
      includeQueryString: true
      maskFields:                 # campos a mascarar
        - "Authorization"
        - "Cookie"
      excludePaths:
        - "/health"
        - "/metrics"
```

#### 6. Configuração POJO

```
[NEW] src/main/java/dev/nishisan/ngate/configuration/AccessLogConfiguration.java
```

### Exemplo de Saída

```json
{
  "traceId": "a1b2c3d4e5f67890",
  "listener": "http",
  "contextName": "default",
  "method": "GET",
  "path": "/api/users/42",
  "queryString": "fields=name,email",
  "clientIp": "192.168.1.100",
  "userAgent": "curl/8.5.0",
  "backend": "users-service",
  "upstreamUrl": "http://users-service:3001/api/users/42?fields=name,email",
  "upstreamStatus": 200,
  "status": 200,
  "returnPipe": true,
  "synthetic": false,
  "totalDuration": 45,
  "rulesDuration": 1,
  "upstreamDuration": 38,
  "responseDuration": 4,
  "authenticated": false,
  "timestamp": "2026-03-08T15:30:00.123-03:00",
  "gatewayVersion": "1.0-SNAPSHOT"
}
```

### Integração com Ferramentas

| Ferramenta | Como ingerir |
|------------|-------------|
| **Elasticsearch** | Filebeat → `log/access.log` → index `ngate-access-*` |
| **Grafana Loki** | Promtail → `log/access.log` → label `{job="ngate"}` |
| **CloudWatch** | Docker log driver → JSON já formatado |
| **jq** | `cat log/access.log \| jq 'select(.status >= 500)'` |

---

## Ordem de Implementação

| Fase | Feature | Estimativa | Dependência |
|------|---------|-----------|-------------|
| 1 | `AccessLogEntry` + `AccessLogEmitter` + `RequestTimings` | ~2h | Nenhuma |
| 2 | Instrumentar pipeline com timings | ~1h | Fase 1 |
| 3 | Configuração `accessLog` no YAML | ~1h | Fase 1 |
| 4 | Log4j2 appender dedicado | ~30min | Fase 1 |
| 5 | `TeeInputStream` + `BodyTeeHandler` | ~2h | Nenhuma |
| 6 | Integrar tee no `HttpResponseAdapter` | ~1h | Fase 5 |
| 7 | Configuração `bodyTee` no YAML | ~1h | Fase 5 |
| 8 | `BodyCaptureSink` implementations (log, file) | ~1.5h | Fase 5 |

> **Recomendação:** Implementar o **Structured Access Log primeiro** (fases 1-4) — é menor, de menor risco e já entrega valor. O Body Tee (fases 5-8) pode ser feito em seguida, aproveitando a infraestrutura de logging já criada.

---

## Referências de Código

| Arquivo | Relevância |
|---------|-----------|
| `HttpResponseAdapter.java` | Fase 5 do streaming (onde o TeeInputStream será encaixado) |
| `HttpProxyManager.handleRequest()` | Onde os timings de rules e upstream são coletados |
| `EndpointWrapper.registerRoutes()` | Bloco finally onde o access log será emitido |
| `HttpWorkLoad.java` | Carrier do `RequestTimings` |
| `EndPointConfiguration.java` | Adicionar campos `bodyTee` e `accessLog` |
