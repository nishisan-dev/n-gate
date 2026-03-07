# Revisão Técnica — inventory-adapter

## 1. Visão Geral do Projeto

O **inventory-adapter** é um API Gateway / Reverse Proxy programável em Java 17, baseado em:
- **Spring Boot 3.5.6** (apenas como bootstrap e DI — o Tomcat é excluído)
- **Javalin 6.5** (como servidor HTTP real, com Jetty/Undertow)
- **OkHttp 4.12** (como client HTTP para os backends)
- **Groovy 3.0.12** (como engine de regras dinâmicas)
- **Brave 6.0.3 + Zipkin Reporter 3.4.0** (para tracing distribuído)
- **Keycloak** (como SSO provider, via OAuth2/JWT)

A ideia é elegante: um proxy que aceita requests, avalia regras Groovy em tempo de execução, roteia para backends configuráveis via YAML, e reporta spans para o Zipkin.

---

## 2. Análise da Integração Zipkin e Observabilidade

### 2.1. Problemas Críticos

#### 🔴 Resource Leak: `Tracing` instâncias nunca são fechadas

Em [TracerService.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/observabitliy/service/TracerService.java#L65-L78):

```java
public Tracer getTracing(String serviceName) {
    if(this.sender == null) {
        this.initSender();
    }
    Tracing tracing = Tracing.newBuilder()
            .localServiceName(serviceName)
            .addSpanHandler(spanHandler)
            .sampler(Sampler.ALWAYS_SAMPLE)
            .build();
    Tracer tracer = tracing.tracer();
    return tracer;
}
```

**Problema:** A cada chamada de `getTracing()`, uma nova instância de `Tracing` é criada e **nunca** fechada (`Tracing` implementa `Closeable`). Isso causa:
- Vazamento de recursos (threads internas do Brave)
- Múltiplas instâncias registradas no `Tracing.current()`, gerando conflitos globais
- O comentário `// don't forget to close!` no `initSender()` é irônico — ele próprio nunca é fechado

**Sugestão:** Cachear a instância de `Tracing` por `serviceName` (um `Map<String, Tracing>`) e implementar `@PreDestroy` ou `DisposableBean` para fazer shutdown graceful.

---

#### 🔴 Sender e Reporter duplicados

O `AsyncReporter` é criado via `AsyncReporter.create(sender)` mas **nunca é utilizado**. O `AsyncZipkinSpanHandler` cria seu próprio reporter internamente via `newBuilder(sender)`. Resultado: há um reporter órfão consumindo memória sem enviar nada.

**Sugestão:** Remover o campo `reporter` e usar apenas o `AsyncZipkinSpanHandler`.

---

#### 🔴 Nenhuma propagação de contexto (Context Propagation)

O tracing distribuído funciona por **propagação de headers** (B3, W3C TraceContext, etc.). O projeto:
- **Não injeta** headers de tracing nas requests para o backend (OkHttp interceptor)
- **Não extrai** traceId/spanId dos headers de entrada (Javalin handler)

Isso significa que os traces no Zipkin são **desconectados**: cada request gera uma árvore de spans isolada. Não há correlação entre o trace do cliente → adapter → backend.

**Sugestão:** Usar `brave-instrumentation-http` (já é dependência no `pom.xml`!) com:
- `HttpServerHandler` para extrair contexto das requests de entrada
- `HttpClientHandler` / OkHttp `TracingInterceptor` para propagar contexto nas requests de saída

---

#### 🟡 Bug no `TracerWrapper.addSpan()`

Em [TracerWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/observabitliy/wrappers/TracerWrapper.java#L40-L44):

```java
public void addSpan(SpanWrapper span) {
    if (this.allSpans.containsKey(span.getName())) {  // ← BUG: deveria ser !containsKey
        this.allSpans.put(span.getName(), span);
    }
}
```

A lógica está **invertida**: o span só é adicionado se já existir (substituição), mas spans novos nunca são registrados. Isso torna o cache de spans inútil e o método `createChildSpan(name, parent)` falha silenciosamente (cai no fallback `currentSpan`).

---

#### 🟡 `getTraceId()` retorna **spanId**, não traceId

Em [TracerWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/observabitliy/wrappers/TracerWrapper.java#L102-L104):

```java
public String getTraceId() {
    return this.currentSpan.getSpan().context().spanIdString(); // ← deveria ser traceIdString()
}
```

O header `x-trace-id` enviado ao cliente contém o **spanId** ao invés do **traceId**. Isso dificulta a correlação no Zipkin.

---

#### 🟡 Sampler.ALWAYS_SAMPLE em produção

O sampler está hardcoded como `ALWAYS_SAMPLE`. Em produção com carga significativa, isso pode:
- Sobrecarregar o collector Zipkin
- Impactar performance da aplicação
- Gerar custos excessivos de armazenamento

**Sugestão:** Tornar configurável via `adapter.yaml` com `RateLimitingSampler` ou `CountingSampler`.

---

### 2.2. Ausências Relevantes de Observabilidade

| Aspecto | Estado Atual | Recomendação |
|---------|-------------|--------------|
| **Métricas (Prometheus/Micrometer)** | ❌ Ausente | Adicionar Micrometer com métricas de latência, taxa de erro, throughput por backend |
| **Health Check** | ❌ Ausente | Spring Actuator com readiness/liveness por backend |
| **Structured Logging** | ❌ Ausente | Injetar traceId/spanId no MDC do Log4j2 para correlação log↔trace |
| **Alertas** | ❌ Ausente | Definir SLIs/SLOs (latência P99, error rate) |
| **Dashboard** | ❌ Ausente | Grafana com dados do Zipkin + métricas |
| **Span annotations** | Parcial | Adicionar `http.status_code`, `http.url`, `peer.service` como tags padrão |

---

## 3. Problemas Gerais de Arquitetura e Código

### 3.1. 🔴 Segurança: `disableSslVerification()` global

Em [InventoryAdapterApplication.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/InventoryAdapterApplication.java#L60-L97):

O método desabilita **toda validação SSL da JVM inteira** — incluindo hostname verification.
Isso afeta **qualquer** conexão HTTPS feita por qualquer biblioteca na mesma JVM. Em produção, isso é uma vulnerabilidade MITM (Man-in-the-Middle) séria.

O mesmo padrão se repete em [HttpProxyManager.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpProxyManager.java#L129-L147) — ao menos aqui é por OkHttpClient, então é isolado ao client.

**Sugestão:** Remover o `disableSslVerification()` global. Se necessário para dev, usar um profile Spring (`dev`) e manter o trust-all **apenas no OkHttpClient** e **nunca na JVM global**.

---

### 3.2. 🟡 Typo no pacote: `observabitliy` (deveria ser `observability`)

O pacote `dev.nishisan.operation.inventory.adapter.observabitliy` tem um typo que se propaga por toda a base de código. Parece menor, mas em projetos que crescem isso vira um incômodo permanente.

---

### 3.3. 🟡 Duplicação massiva no `EndpointWrapper`

O [EndpointWrapper.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/EndpointWrapper.java) tem **duas cópias quase-idênticas** da lógica de handler:
1. Handler para método específico (linhas 110-168)
2. Handler para `ANY` (linhas 171-230)

A lógica de security check, span creation, e proxy dispatch é 95% idêntica. Isso dificulta manutenção e é propenso a divergências.

**Sugestão:** Extrair para um método `handleWithTracing(ctx, contextName, urlContext, tracer, listenerConfig, name)`.

---

### 3.4. 🟡 `HttpProxyManager` — God Class

O [HttpProxyManager.java](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpProxyManager.java) (677 linhas) acumula responsabilidades de:
- Factory de HTTP clients
- SSL configuration
- Groovy script execution
- Request routing
- Response handling

**Sugestão:** Separar em:
- `HttpClientFactory` — criação e cache de `OkHttpClient`
- `GroovyRuleEngine` — avaliação de regras
- `ProxyRequestHandler` — orquestração do fluxo

---

### 3.5. 🟡 Conflito Spring Boot ↔ Javalin

O projeto usa Spring Boot como bootstrap mas Javalin como HTTP server. Isso cria redundância:
- Spring Boot sobe o Undertow na porta 18080 (basicamente sem endpoints)
- Javalin sobe na porta 9090 (onde o tráfego real acontece)

The Spring context is mainly used for DI (`@Autowired`). Toda a lógica HTTP vive fora do Spring MVC.

**Sugestão:** Avaliar se o Spring Boot é realmente necessário ou se o projeto poderia ser simplificado com Javalin + Guice/Dagger, eliminando o overhead do Spring e a porta ociosa.

---

### 3.6. 🟡 `Groovy 3.0.12` + `Kotlin stdlib` no classpath

O projeto usa Groovy para regras dinâmicas, mas também tem `kotlin-stdlib` e `kotlin-reflect` como dependências (vindas do Javalin). Se não há código Kotlin no projeto, considerar verificar se são transitivas e se podem ser reduzidas.

---

### 3.7. 🟡 Ausência de testes

Existe apenas **1 teste** (`ConfigTest.java`), o que indica cobertura mínima. O CI (`gitlab-ci.yml`) roda `mvn test` mas efetivamente testa quase nada.

---

### 3.8. 🟡 `ContextRunnerThread` — código morto

A inner class [ContextRunnerThread](file:///home/lucas/Projects/inventory-adapter/src/main/java/dev/nishisan/operation/inventory/adapter/http/HttpProxyManager.java#L626-L674) nunca é utilizada. O `threadPool` criado em `init()` também parece não ser usado.

---

## 4. Sugestões Prioritárias (Ordenadas por Impacto)

### Imediato (Segurança / Correção de Bugs)

| # | Item | Severidade |
|---|------|-----------|
| 1 | Corrigir `addSpan()` — lógica invertida | 🔴 Bug |
| 2 | Corrigir `getTraceId()` → `traceIdString()` | 🔴 Bug |
| 3 | Remover `disableSslVerification()` global | 🔴 Segurança |
| 4 | Fechar `Tracing` instances (resource leak) | 🔴 Resource Leak |
| 5 | Remover `AsyncReporter` órfão | 🟡 Cleanup |

### Curto Prazo (Observabilidade Funcional)

| # | Item | Impacto |
|---|------|---------|
| 6 | Implementar context propagation (B3 headers) com `brave-instrumentation-http` | 🔴 Crítico para distributed tracing |
| 7 | Injetar traceId no MDC do Log4j2 | 🟡 Correlação log↔trace |
| 8 | Sampler configurável via YAML | 🟡 Produção |
| 9 | Tags padrão nos spans (status_code, url, peer.service) | 🟡 Qualidade dos traces |

### Médio Prazo (Qualidade / Manutenibilidade)

| # | Item | Impacto |
|---|------|---------|
| 10 | Refatorar `EndpointWrapper` — eliminar duplicação | 🟡 Manutenibilidade |
| 11 | Refatorar `HttpProxyManager` — separar responsabilidades | 🟡 Manutenibilidade |
| 12 | Adicionar métricas Micrometer + health checks | 🟡 Operacional |
| 13 | Cobertura de testes | 🟡 Qualidade |
| 14 | Corrigir typo `observabitliy` → `observability` | 🟢 Qualidade do pacote |
| 15 | Remover código morto (`ContextRunnerThread`, `handleGet`, `reporter` órfão) | 🟢 Cleanup |
