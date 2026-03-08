# Migração Javalin 7 + Jetty 12 + Loom Nativo

Migrar o `inventory-adapter` de **Javalin 6.5.0 + Jetty 11.0.24** para **Javalin 7.x + Jetty 12** para obter suporte nativo a Virtual Threads (Loom), melhor throughput e manter-se em stack suportada (Jetty 11 é EOL).

## User Review Required

> [!IMPORTANT]
> **Virtual Threads nativo** — O Javalin 7 tem `config.concurrency.useVirtualThreads = true` que habilita Loom em todas as camadas do server. Isso vai substituir completamente o setup manual atual com `QueuedThreadPool` + `setVirtualThreadsExecutor`. Os campos `jettyMinThreads`, `jettyMaxThreads` e `jettyIdleTimeout` no `adapter.yaml` se tornam obsoletos para o Javalin (o OkHttp continua usando virtual threads pelo Dispatcher independente).

> [!WARNING]
> **Rotas devem ser upfront** — No Javalin 7, `addHttpHandler()` após `start()` não é mais válido. A arquitetura atual do `EndpointWrapper` registra handlers após `Javalin.create()` e depois chama `start()`. Precisamos garantir que o `addServiceListener` registre tudo antes do `start()`.

> [!IMPORTANT]
> **`HandlerType` agora é Record** — No código atual, `HandlerType` é usado como enum. No Javalin 7 é um record. O `Map.of()` com `HandlerType.GET` etc. deve continuar funcionando, mas precisa-se verificar se a comparação/referência permanece compatível.

## Proposed Changes

### Dependências (pom.xml)

Atualizar as coordenadas Maven para alinhar com Javalin 7 + Jetty 12.

#### [MODIFY] [pom.xml](file:///home/lucas/Projects/inventory-adapter/pom.xml)

1. **Remover `jetty-bom` 11.0.24** — O Javalin 7 traz Jetty 12 transitivamente, não é necessário sobrescrever o BOM
2. **Atualizar `io.javalin:javalin`** de `6.5.0` para `7.0.0`
3. **Atualizar `io.javalin.community.ssl:ssl-plugin`** de `6.5.0` para `7.0.0`
4. **Remover** `jakarta.servlet-api 6.1.0` (provided) — Jetty 12 ee11 traz automaticamente
5. **Kotlin stdlib** — Javalin 7 não traz mais transitivamente. Manter `kotlin-stdlib` e `kotlin-reflect` explícitos (já estão no pom)

---

### Configuração do Servidor Javalin

#### [MODIFY] [EndpointManager.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/manager/EndpointManager.java)

Mudanças principais:
1. **Remover imports** de `org.eclipse.jetty.util.thread.QueuedThreadPool` e `org.eclipse.jetty.util.ssl.SslContextFactory`
2. **Remover `java.util.concurrent.Executors`** (usado para VirtualThreadsExecutor)  
3. **Substituir** `QueuedThreadPool` + `setVirtualThreadsExecutor()` por `javalinConfig.useVirtualThreads = true`
4. **Thread Pool**: Usar `javalinConfig.jetty.threadPool` com configuração simplificada ou confiar no default do Javalin 7 com Loom
5. **SSL Plugin**: Manter lógica de `SslPlugin` — é compatível com Javalin 7
6. **Banner**: `javalinConfig.showJavalinBanner = false` continua válido
7. **`javalinConfig.jetty.threadPool`** — No Javalin 7, o threadPool é gerenciado pelo framework quando VirtualThreads estão habilitados. Remover configuração manual de `QueuedThreadPool`

```diff
-import org.eclipse.jetty.util.ssl.SslContextFactory;
-import org.eclipse.jetty.util.thread.QueuedThreadPool;
-import java.util.concurrent.Executors;
```

```diff
-QueuedThreadPool threadPool = new QueuedThreadPool(
-    endPoingConfiguration.getJettyMaxThreads(),
-    endPoingConfiguration.getJettyMinThreads(),
-    endPoingConfiguration.getJettyIdleTimeout());
-threadPool.setName("JettyServerThreadPool");
-threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
-javalinConfig.jetty.threadPool = threadPool;
+javalinConfig.useVirtualThreads = true;
```

#### [MODIFY] [EndpointManager.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/manager/EndpointManager.java) — método `getFactoryFromConfig`

- **Remover o método** `getFactoryFromConfig` — usa `SslContextFactory.Server` do Jetty 11 diretamente e já estava sem uso real (a lógica de SSL usa o `SslPlugin`)

---

### Handlers HTTP

#### [MODIFY] [EndpointWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/EndpointWrapper.java)

1. **`HandlerType`** — Verificar se `Map.of("GET", HandlerType.GET, ...)` continua compilando pois agora é record, não enum. Caso não compile, usar `HandlerType.findOrCreate("GET")` etc.
2. **`listener.addHttpHandler()`** — Validar que esse método continua presente no Javalin 7. No Javalin 7, como rotas devem ser upfront, teremos que garantir que `addHttpHandler` é chamado ANTES de `listener.start()` (já é o caso no código atual)
3. **`ctx.matchedPath()`** — usado no `CustomContextWrapper`, agora é `ctx.endpoint().path()`. Atualizar o wrapper
4. **`listener.start(address, port)`** — No Javalin 7 usa `config.jetty.host` e `config.jetty.port`. Precisaremos ajustar a forma de start passando host/port via config em vez de `start(addr, port)`

---

### Context Wrapper

#### [MODIFY] [CustomContextWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/CustomContextWrapper.java)

1. **`matchedPath()`** (linha 126) → substituir por `endpoint().path()` 
2. **`endpointHandlerPath()`** (linha 131) → verificar se método ainda existe no Javalin 7 `Context` interface
3. **Verificar interface `Context`** — garantir que todos os métodos delegados (677 linhas) ainda existem no Javalin 7. As mudanças de API no Javalin 7 podem ter adicionado/removido métodos na interface

---

### Configuração do Endpoint

#### [MODIFY] [EndPointConfiguration.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/configuration/EndPointConfiguration.java)

- **Deprecar** campos `jettyMinThreads`, `jettyMaxThreads`, `jettyIdleTimeout` — não serão mais usados com Loom nativo. Manter os getters/setters para compatibilidade YAML mas adicionar `@Deprecated`

---

### Servlet Wrappers (Impacto Mínimo)

#### Os seguintes arquivos usam `jakarta.servlet` 6.1 — nenhuma mudança necessária

- [HttpAdapterServletRequest.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpAdapterServletRequest.java) — Usa `jakarta.servlet.http.*` ✅ compatível
- [HttpAdapterServletResponse.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpAdapterServletResponse.java) — Usa `jakarta.servlet.http.*` ✅ compatível
- [DelegatingServletOutputStream.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/DelegatingServletOutputStream.java) — Usa `jakarta.servlet.ServletOutputStream` ✅ compatível
- [SyntHttpResponse.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/synth/response/SyntHttpResponse.java) — Usa `jakarta.servlet.http.HttpServletResponse` ✅ compatível

> [!NOTE]
> O Jetty 12 suporta `ee10` (Jakarta Servlet 6.0) e `ee11` (Jakarta Servlet 6.1). O projeto já usa Jakarta Servlet 6.1, portanto é compatível com Jetty 12 ee11 sem mudanças.

---

### Planning Artifact

#### [NEW] [javalin7_migration.md](file:///home/lucas/Projects/inventory-adapter/planning/javalin7_migration.md)

Copiar este plano aprovado para a pasta `/planning` do projeto.

## Verification Plan

### Build Automatizado
```bash
cd /home/lucas/Projects/inventory-adapter
mvn clean install -DskipTests
```

O build deve compilar sem erros. Erros de compilação indicarão incompatibilidades de API não mapeadas.

### Smoke Test com Docker
```bash
cd /home/lucas/Projects/inventory-adapter
docker compose down inventory-adapter
docker compose build inventory-adapter
docker compose up -d inventory-adapter
docker compose logs -f inventory-adapter
```

Verificar no log que:
1. O endpoint Javalin inicia sem errors
2. "Virtual threads" ou similar aparece nos logs de startup
3. O endpoint responde a requests HTTP

### Teste Manual (solicito orientação do usuário)

> [!IMPORTANT]
> O projeto tem apenas um teste stub vazio (`ConfigTest.java`). Sugiro que o usuário valide manualmente enviando um request ao endpoint configurado no `adapter.yaml` após o build Docker. Qual endpoint/URL seria ideal para testar?
