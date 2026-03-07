# Plano de Implementação — Otimizações de Performance do Inventory Adapter

Otimizações de baixa/média complexidade focadas em reduzir latência e alocações no hot path de requests do proxy reverso. O tracing (item 8 da análise) é **inegociável** e será preservado integralmente.

> [!IMPORTANT]
> Todas as mudanças são internas ao runtime. Não há breaking changes em contratos, configurações YAML, ou comportamento externo do proxy.

---

## Proposed Changes

### Componente 1 — SSL & Connection Pool (P0)

Extrair o `SSLContext` e `TrustManager` para campos estáticos reutilizáveis e criar um `ConnectionPool` compartilhado.

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpProxyManager.java)

1. **SSLContext estático** — Criar campos `private static final` para `X509TrustManager TRUST_ALL_MANAGER`, `SSLContext SHARED_SSL_CONTEXT`, e `SSLSocketFactory SHARED_SSL_FACTORY` em um bloco `static {}`. Remover toda a criação de SSL dentro de `getHttpClientByListenerName()`.
2. **ConnectionPool compartilhado** — Criar campo `private final ConnectionPool sharedConnectionPool = new ConnectionPool(50, 5, TimeUnit.MINUTES)`. Adicionar `.connectionPool(sharedConnectionPool)` em todos os 3 builders de `OkHttpClient`.
3. **HostnameVerifier compartilhado** — Criar constante `private static final HostnameVerifier TRUST_ALL_HOSTNAMES = (hostname, session) -> true` e reutilizar nos builders.

---

### Componente 2 — Lazy Body Loading & Buffer Size (P1)

#### [MODIFY] [HttpAdapterServletRequest.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpAdapterServletRequest.java)

1. **Remover `copyBody()` do construtor** — O body não será copiado eagerly. O campo `body` passa a ser `null` por padrão.
2. **Lazy loading em `getBodyAsBytes()`** — Apenas na primeira chamada, ler o `InputStream` com **buffer de 8192 bytes** (ao invés de 1024) e cachear no campo `body`.
3. **Guardar referência ao `InputStream`** — Salvar `this.baseRequest.getInputStream()` como campo para leitura futura lazy.

---

### Componente 3 — Groovy Recompilation Interval (P1)

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpProxyManager.java)

No método `initGse()`, após criar o `GroovyScriptEngine`, configurar:

```java
private void initGse() throws IOException {
    this.gse = new GroovyScriptEngine("rules");
    CompilerConfiguration config = this.gse.getConfig();
    config.setRecompileGroovySource(true);
    config.setMinimumRecompilationInterval(60); // 60 segundos
}
```

Isso mantém a capacidade de hot-reload dos scripts Groovy mas evita o stat/recompile a cada request. O intervalo mínimo de 60s é conservador.

Import necessário: `org.codehaus.groovy.control.CompilerConfiguration`.

---

### Componente 4 — Cachear `res()` no CustomContextWrapper (P2)

#### [MODIFY] [CustomContextWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/CustomContextWrapper.java)

Cachear o `HttpAdapterServletResponse` na primeira chamada a `res()`:

```java
private HttpAdapterServletResponse cachedResponse;

@Override
public HttpAdapterServletResponse res() {
    if (this.cachedResponse == null) {
        this.cachedResponse = new HttpAdapterServletResponse(context.res());
    }
    return this.cachedResponse;
}
```

---

### Componente 5 — Guard de Logging no Hot Path (P2)

#### [MODIFY] [HttpRequestAdapter.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpRequestAdapter.java)

1. **Remover `new String(request.getBodyAsBytes())`** na linha 75 — Substituir por log do tamanho:
```java
if (logger.isDebugEnabled()) {
    logger.debug("Body content length: [{}]", request.getBodyAsBytes().length);
}
```
2. Proteger os loops de header dump com `if (logger.isDebugEnabled())`.

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpProxyManager.java)

1. Proteger os loops de header dump nos interceptors (linhas 180-183, 220-223, 270-274) com `if (logger.isDebugEnabled())`.
2. Substituir concatenação de strings nas mensagens de log por placeholders paramétricos (`{}`) onde ainda houver `"foo" + bar`.

---

### Componente 6 — Micro-otimizações (P3)

#### [MODIFY] [HttpWorkLoad.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpWorkLoad.java)

1. `String body = new String()` → `String body = ""` (evita alocação de objeto).
2. `ConcurrentSkipListMap` → `ConcurrentHashMap` nas duas declarações (se não precisa de ordenação — o `ConcurrentSkipListMap` adiciona overhead de O(log n) por operação).

#### [MODIFY] [EndpointWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/EndpointWrapper.java)

1. No `getTokenDecoder`, substituir `Date` + `SimpleDateFormat` por `Instant.now()` + `isAfter()`:
```java
Instant now = Instant.now();
if (now.isAfter(decoder.getRecreateInstant())) { ... }
```

> [!WARNING]
> A mudança de `Date` para `Instant` em `getTokenDecoder` propaga para a interface `ITokenDecoder.getRecreateDate()`. Para evitar quebra de contrato, a abordagem será manter `getRecreateDate()` mas converter internamente para `Instant` na comparação, sem alterar a interface.

---

### Componente 7 — ResponseStream com pre-alocação (P2)

#### [MODIFY] [SyntHttpResponse.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/synth/response/SyntHttpResponse.java)

Aumentar o tamanho inicial do `ByteArrayOutputStream` de 1024 para 8192 bytes para reduzir realocações em respostas maiores:

```java
private final ByteArrayOutputStream content = new ByteArrayOutputStream(8192);
```

---

## Itens Excluídos

| Item | Motivo |
|------|--------|
| **Item 8 — Tracing/spans** | Inegociável — manter granularidade atual |
| **Response returnPipe como default** | Depende do uso real dos scripts — risco de quebra funcional. Fica para análise futura |

---

## Verificação

### Build Automático

```bash
cd /home/lucas/Projects/inventory-adapter && mvn clean package -DskipTests
```

Valida que todas as mudanças compilam sem erros.

### Teste Unitário Existente

```bash
cd /home/lucas/Projects/inventory-adapter && mvn test
```

Há um teste existente em `ConfigTest.java` que valida configuração. Deve continuar passando.

### Verificação Manual (Recomendada)

Como o projeto não possui testes unitários cobrindo o hot path HTTP, a verificação funcional mais segura é:

1. Reconstruir a imagem Docker e subir o container:
```bash
cd /home/lucas/Projects/inventory-adapter && docker compose down inventory-adapter && docker compose up -d --build inventory-adapter
```
2. Verificar nos logs do container que o adapter sobe sem erros:
```bash
docker compose logs -f inventory-adapter
```
3. Fazer uma request de teste ao endpoint configurado e confirmar que:
   - A resposta retorna normalmente
   - O header `x-trace-id` está presente
   - Os traces aparecem no Zipkin

> [!NOTE]
> Peço que me indique qual endpoint/URL posso usar para teste funcional, ou se prefere validar manualmente após o deploy.
