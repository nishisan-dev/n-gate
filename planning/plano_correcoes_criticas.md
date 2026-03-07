# Plano de Correção — Itens Críticos (🔴)

Referência: [revisao_tecnica.md](file:///home/lucas/Projects/inventory-adapter/planning/revisao_tecnica.md)

Este plano cobre os **6 itens críticos** identificados na revisão técnica.
Todos são bugs, resource leaks ou vulnerabilidades de segurança.

---

## 1. Correção do Bug `addSpan()` — Lógica Invertida

### Problema
`TracerWrapper.addSpan()` usa `containsKey` ao invés de `!containsKey`, impedindo que spans novos sejam registrados no mapa.

### Alteração

#### [MODIFY] [TracerWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/observabitliy/wrappers/TracerWrapper.java)

```diff
 public void addSpan(SpanWrapper span) {
-    if (this.allSpans.containsKey(span.getName())) {
+    if (!this.allSpans.containsKey(span.getName())) {
         this.allSpans.put(span.getName(), span);
     }
 }
```

> [!NOTE]
> A semântica correta é: _adicionar se não existir_. A implementação atual silenciosamente descarta spans novos e só atualiza duplicatas — exatamente o oposto do esperado.

---

## 2. Correção do Bug `getTraceId()` — Retorna spanId

### Problema
O método `getTraceId()` retorna `spanIdString()` ao invés de `traceIdString()`. O header `x-trace-id` enviado ao cliente contém o valor errado.

### Alteração

#### [MODIFY] [TracerWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/observabitliy/wrappers/TracerWrapper.java)

```diff
 public String getTraceId() {
-    return this.currentSpan.getSpan().context().spanIdString();
+    return this.currentSpan.getSpan().context().traceIdString();
 }
```

---

## 3. Remoção do `disableSslVerification()` Global

### Problema
O método `disableSslVerification()` no `InventoryAdapterApplication.main()` desabilita toda validação SSL da JVM, afetando todas as libs. É uma vulnerabilidade MITM.

### Alteração

#### [MODIFY] [InventoryAdapterApplication.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/InventoryAdapterApplication.java)

- Remover completamente o método `disableSslVerification()`.
- Remover a chamada `disableSslVerification()` do `main()`.
- O trust-all já existe isolado no `HttpProxyManager` por `OkHttpClient` — esse é o caminho correto (por client, não global). Futuramente, esse trust-all no OkHttpClient também deveria ser condicional via profile, mas não é escopo deste plano.

```diff
 public static void main(String[] args) {
     logger.debug("Starting...");
-    disableSslVerification();
     SpringApplication.run(InventoryAdapterApplication.class, args);
 }
-
-private static void disableSslVerification() {
-    // ... todo o método removido ...
-}
```

Remover também os imports não utilizados:
- `java.security.KeyManagementException`
- `java.security.NoSuchAlgorithmException`
- `java.security.cert.X509Certificate`
- `javax.net.ssl.HostnameVerifier`
- `javax.net.ssl.HttpsURLConnection`
- `javax.net.ssl.SSLContext`
- `javax.net.ssl.SSLSession`
- `javax.net.ssl.TrustManager`
- `javax.net.ssl.X509TrustManager`

---

## 4. Resource Leak — Cachear e Fechar `Tracing` Instances

### Problema
`TracerService.getTracing()` cria uma nova instância de `Tracing` a cada chamada, nunca fechando as anteriores. `Tracing` implementa `Closeable` e gerencia threads internas.

### Alteração

#### [MODIFY] [TracerService.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/observabitliy/service/TracerService.java)

- Adicionar um `Map<String, Tracing>` para cachear instâncias por `serviceName`.
- Retornar o `Tracer` de instâncias cacheadas quando já existir.
- Implementar `@PreDestroy` para fechar todas as instâncias no shutdown.
- Fechar também o sender e o spanHandler no shutdown.

```java
@Service
public class TracerService implements DisposableBean {

    private static final String DEFAULT_ZIPKIN_ENDPOINT = "http://zipkin:9411/api/v2/spans";

    private OkHttpSender sender;
    private AsyncZipkinSpanHandler spanHandler;
    private final Map<String, Tracing> tracingInstances = new ConcurrentHashMap<>();

    // ... resolveZipkinEndpoint() sem alteração ...

    private synchronized void initSender() {
        if (this.sender != null) return; // double-check
        this.sender = OkHttpSender.newBuilder().endpoint(resolveZipkinEndpoint()).build();
        this.spanHandler = AsyncZipkinSpanHandler.newBuilder(sender)
                .alwaysReportSpans(true)
                .build();
    }

    public Tracer getTracing(String serviceName) {
        if (this.sender == null) {
            this.initSender();
        }
        Tracing tracing = tracingInstances.computeIfAbsent(serviceName, name ->
            Tracing.newBuilder()
                    .localServiceName(name)
                    .addSpanHandler(spanHandler)
                    .sampler(Sampler.ALWAYS_SAMPLE)
                    .build()
        );
        return tracing.tracer();
    }

    @Override
    public void destroy() {
        tracingInstances.values().forEach(Tracing::close);
        tracingInstances.clear();
        if (spanHandler != null) spanHandler.close();
        if (sender != null) sender.close();
    }
}
```

> [!IMPORTANT]
> O campo `AsyncReporter<Span> reporter` será removido nesta mesma mudança (item 5 abaixo).

---

## 5. Remoção do `AsyncReporter` Órfão

### Problema
O campo `reporter` é criado via `AsyncReporter.create(sender)` mas nunca é utilizado. O `AsyncZipkinSpanHandler` cria seu próprio reporter internamente.

### Alteração

#### [MODIFY] [TracerService.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/observabitliy/service/TracerService.java)

- Remover o campo `private AsyncReporter<Span> reporter`.
- Remover a linha `this.reporter = AsyncReporter.create(sender)` do `initSender()`.
- Remover o import `zipkin2.reporter.AsyncReporter`.

> Esta alteração é feita junto com o item 4 (mesmo arquivo).

---

## 6. Implementar Context Propagation (B3 Headers)

### Problema
Não há propagação de contexto de tracing entre requests de entrada → adapter → backend. Cada request gera uma árvore de spans completamente isolada no Zipkin. A dependência `brave-instrumentation-http` já está no `pom.xml` mas não é utilizada.

### Alteração

Este é o item mais impactante. Requer alterações em dois pontos:

#### 6a. Extrair contexto B3 das requests de entrada

#### [MODIFY] [EndpointWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/EndpointWrapper.java)

Na criação do `TracerWrapper`, ao invés de sempre criar um novo trace via `traceWrapper.createSpan()`, verificar se a request de entrada já possui headers B3 (`X-B3-TraceId`, `X-B3-SpanId`, `X-B3-Sampled` ou header único `b3`). Se sim, criar o span como child do contexto extraído.

Usar `brave.propagation.B3Propagation` + `TraceContext.Extractor` para extrair o contexto dos headers da `HttpServletRequest`.

```java
// Na criação do handler, ao invés de:
TracerWrapper traceWrapper = new TracerWrapper(tracer);
SpanWrapper rootSpan = traceWrapper.createSpan("...");

// Fazer:
TraceContext.Extractor<HttpServletRequest> extractor = tracing.propagation()
        .extractor(HttpServletRequest::getHeader);
TraceContextOrSamplingFlags extracted = extractor.extract(ctx.req());
Span rootBraveSpan = extracted.context() != null
        ? tracer.joinSpan(extracted.context())
        : tracer.newTrace();
rootBraveSpan.name("...").kind(Span.Kind.SERVER).start();
```

> [!IMPORTANT]
> Isso requer que o `TracerService` exponha a instância de `Tracing` (não apenas o `Tracer`), pois precisamos do `tracing.propagation()`. Será adicionado um método `getTracing(serviceName)` que retorna o `Tracing`, e o existente será renomeado para `getTracer(serviceName)`.

#### 6b. Injetar contexto B3 nas requests para o backend

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpProxyManager.java)

No interceptor do OkHttpClient ou no ponto onde o `Request` é criado para o backend, injetar os headers B3 a partir do span ativo.

Usar `TraceContext.Injector` com o builder da request OkHttp:

```java
TraceContext.Injector<Request.Builder> injector = tracing.propagation()
        .injector(Request.Builder::addHeader);
injector.inject(currentSpan.context(), requestBuilder);
```

Para isso, o `HttpProxyManager` precisa receber acesso ao `Tracing` instance (via construtor ou injeção). O span ativo pode ser obtido do `TracerWrapper` que já existe no `CustomContextWrapper`.

#### 6c. Ajustar `TracerService` para expor `Tracing`

#### [MODIFY] [TracerService.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/observabitliy/service/TracerService.java)

Adicionar método que retorna a instância `Tracing`:

```java
public Tracing getTracingInstance(String serviceName) {
    if (this.sender == null) {
        this.initSender();
    }
    return tracingInstances.computeIfAbsent(serviceName, name ->
        Tracing.newBuilder()
                .localServiceName(name)
                .addSpanHandler(spanHandler)
                .sampler(Sampler.ALWAYS_SAMPLE)
                .build()
    );
}

public Tracer getTracer(String serviceName) {
    return getTracingInstance(serviceName).tracer();
}
```

#### Arquivos alterados no item 6:
- `TracerService.java` (já alterado nos itens 4/5)
- `EndpointWrapper.java`
- `HttpProxyManager.java`
- `CustomContextWrapper.java` (pode precisar carregar o `Tracing` instance para a propagação)

---

## Verificação

### Testes Automatizados

> [!WARNING]
> O projeto possui apenas um teste placeholder (`ConfigTest.java`) que não exercita nenhuma funcionalidade. Não há infraestrutura de testes unitários real.

Para verificar as correções:

1. **Compilação:** Executar `mvn clean compile` para garantir que todas as alterações compilam.
   ```bash
   cd /home/lucas/Projects/inventory-adapter && mvn clean compile
   ```

2. **Teste de integração com Docker Compose:** Subir o ambiente via `docker compose up --build` e:
   - Fazer uma request via adapter: `curl -i http://localhost:9090/realms/inventory-dev/.well-known/openid-configuration`
   - Verificar no Zipkin (`http://localhost:9411`) se os traces mostram:
     - Span de entrada (SERVER) com traceId consistente
     - Span de saída (CLIENT) como child do span de entrada
     - Tags `http.status_code`, `http.url`, etc.
   - Verificar que o header `x-trace-id` na resposta contém o traceId correto (não o spanId)

### Verificação Manual

O usuário deverá validar manualmente:

1. **Zipkin UI** — abrir `http://localhost:9411`, buscar pelo serviço `http`, e confirmar que agora os traces mostram a hierarquia completa (entry → rules → upstream-request) com o mesmo traceId
2. **Header `x-trace-id`** — confirmar que o valor retornado é um traceId válido (32 hex chars) e não um spanId (16 hex chars)
3. **Sem erros de SSL** — confirmar que a aplicação ainda conecta corretamente ao Keycloak backend (o trust-all está no OkHttpClient, não na JVM global)

---

## Ordem de Execução

| Passo | Item | Arquivo(s) | Risco |
|-------|------|-----------|-------|
| 1 | Bug `addSpan()` | `TracerWrapper.java` | Baixo |
| 2 | Bug `getTraceId()` | `TracerWrapper.java` | Baixo |
| 3 | Resource Leak + Reporter Órfão | `TracerService.java` | Médio |
| 4 | SSL Global | `InventoryAdapterApplication.java` | Médio |
| 5 | Context Propagation | `TracerService.java`, `EndpointWrapper.java`, `HttpProxyManager.java` | Alto |
| 6 | Compilação + Smoke Test | — | — |
