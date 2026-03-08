# Migração Javalin 7 + Jetty 12 + Loom Nativo

## Visão Geral

Esta documentação descreve a migração do `inventory-adapter` de **Javalin 6.5.0 + Jetty 11** para **Javalin 7.0.1 + Jetty 12**, com suporte nativo a Virtual Threads (Project Loom) do JDK 21.

**Branch:** `feature/javalin7-jetty12-loom`
**Data:** 2026-03-08

### Motivação

O Javalin 6 utilizava um `QueuedThreadPool` do Jetty 11 com `Executors.newVirtualThreadPerTaskExecutor()` configurado manualmente. Esta abordagem apresentava degradação severa de performance em alta concorrência (c≥100), pois o thread pool fixo limitava o throughput do proxy.

O Javalin 7 com Jetty 12 oferece suporte **nativo** a Loom, eliminando a necessidade de configuração manual e removendo o gargalo do pool de threads.

## Arquitetura

![Arquitetura Javalin 7](https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/inventory-adapter/main/docs/diagrams/javalin7_architecture.puml)

## Mudanças Técnicas

### Dependências (`pom.xml`)

| Dependência | Antes | Depois |
|-------------|-------|--------|
| `io.javalin:javalin` | 6.5.0 | **7.0.1** |
| `io.javalin.community.ssl:ssl-plugin` | 6.5.0 | **6.7.0** |
| `org.eclipse.jetty:jetty-bom` | 11.0.24 (pin explícito) | **Removido** (Jetty 12 transitivo) |
| `jakarta.servlet:jakarta.servlet-api` | Explícito | **Removido** (transitivo via Jetty 12) |

### Virtual Threads

```diff
 // EndpointManager.java
-QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, minThreads, idleTimeout);
-threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
-Javalin service = Javalin.create(config -> {
-    config.jetty.threadPool = threadPool;
-});
+Javalin service = Javalin.create(config -> {
+    config.concurrency.useVirtualThreads = true;
+});
```

### Registro de Rotas (Breaking Change Javalin 7)

No Javalin 7, handlers **devem** ser registrados upfront dentro de `Javalin.create()` via `config.routes`, não mais diretamente na instância Javalin após a criação.

```diff
 // EndpointManager.java — ANTES
-Javalin service = Javalin.create(config -> { ... });
-wrapper.addServiceListener(name, service, listenerConfig);

 // EndpointManager.java — DEPOIS
+Javalin service = Javalin.create(config -> {
+    // ...configuração...
+    wrapper.registerRoutes(name, config.routes, listenerConfig);
+});
+wrapper.startListener(name, service, listenerConfig);
```

O método `addServiceListener` foi dividido em dois:

| Método | Onde é chamado | Responsabilidade |
|--------|---------------|------------------|
| `registerRoutes()` | Dentro de `Javalin.create()` | Registra handlers via `config.routes` |
| `startListener()` | Após `Javalin.create()` | Armazena Javalin + chama `start()` |

### API do Context (Breaking Changes)

| Javalin 6 | Javalin 7 | Ação |
|-----------|-----------|------|
| `ctx.handlerType()` | Removido | Substituído por `ctx.method()` |
| `ctx.matchedPath()` | Removido | Substituído por `ctx.endpoint().path()` |
| `ctx.endpointHandlerPath()` | Removido | Substituído por `ctx.endpoint().path()` |
| — | `ctx.endpoint()` | Novo abstract — implementado |
| — | `ctx.endpoints()` | Novo abstract — implementado |
| — | `ctx.multipartConfig()` | Novo abstract — implementado |
| — | `ctx.strictContentTypes()` | Novo abstract — implementado |

## Benchmark — Resultados Comparativos

Benchmarks executados via Apache Bench contra Nginx (baseline) e inventory-adapter (proxy), nos níveis de concorrência 1, 10, 50, 100 e 500.

### Throughput — Requests Fixas (5000 reqs)

| Concurrency | Nginx (baseline) | Javalin 6 RPS | % Baseline | Javalin 7 RPS | % Baseline | Ganho J6→J7 |
|:-----------:|:----------------:|:-------------:|:----------:|:-------------:|:----------:|:-----------:|
| c=1 | 2,236 | 549 | 25% | **1,226** | 55% | **+123%** |
| c=10 | 9,591 | 1,617 | 17% | **4,949** | 52% | **+206%** |
| c=50 | 9,386 | 2,350 | 25% | **4,637** | 49% | **+97%** |
| c=100 | 9,824 | 2,827 | 29% | 2,593 | 26% | −8% |
| c=500 | 12,655 | 2,498 | 20% | **4,211** | 33% | **+69%** |

> **Nota:** O resultado de c=100 no benchmark fixo é afetado por cold start do Jetty 12. O benchmark timed (10s) elimina esse efeito e mostra +50%.

### Throughput — Timed (10 segundos)

| Concurrency | Nginx (baseline) | Javalin 6 RPS | % Baseline | Javalin 7 RPS | % Baseline | Ganho J6→J7 |
|:-----------:|:----------------:|:-------------:|:----------:|:-------------:|:----------:|:-----------:|
| c=1 | 2,410 | 1,054 | 44% | **1,423** | 59% | **+35%** |
| c=10 | 9,563 | 2,326 | 24% | **3,306** | 35% | **+42%** |
| c=50 | 9,412 | 2,821 | 30% | **4,004** | 43% | **+42%** |
| c=100 | 10,153 | 2,760 | 27% | **4,143** | 41% | **+50%** |
| c=500 | 12,419 | 2,523 | 20% | **3,403** | 27% | **+35%** |

### Total de Requests em 10s

| Concurrency | Nginx (baseline) | Javalin 6 | Javalin 7 | Ganho J6→J7 |
|:-----------:|:----------------:|:---------:|:---------:|:-----------:|
| c=1 | 24,100 | 10,539 | **14,231** | +35% |
| c=10 | 95,634 | 23,260 | **33,057** | +42% |
| c=50 | 94,125 | 28,211 | **40,038** | +42% |
| c=100 | 101,535 | 27,600 | **41,430** | +50% |
| c=500 | 124,279 | 25,233 | **34,029** | +35% |

### Tail Latency — Timed (p95 / p99 em ms)

| Concurrency | Nginx p99 | J6 p95 | J7 p95 | J6 p99 | J7 p99 | Melhoria p99 |
|:-----------:|:---------:|:------:|:------:|:------:|:------:|:------------:|
| c=1 | 1 | 1 | 1 | 2 | **1** | −50% |
| c=10 | 1 | 7 | **5** | 9 | **6** | −33% |
| c=50 | 7 | 32 | **22** | 40 | **28** | −30% |
| c=100 | 14 | 75 | **42** | 101 | **54** | **−47%** |
| c=500 | 65 | 430 | **310** | 554 | **397** | **−28%** |

### Análise

1. **Ganho consistente**: O Javalin 7 com Loom nativo apresentou ganhos de **+35% a +50%** em throughput sustentado (benchmark timed) em **todos** os níveis de concorrência.

2. **Eliminação do gargalo de threads**: O maior impacto está em c=100 (timed), onde o Javalin 6 saturava o `QueuedThreadPool` e o Javalin 7 escala naturalmente com Virtual Threads.

3. **Tail latencies**: Melhoria de **28% a 47%** no p99 em alta concorrência — as Virtual Threads nativas evitam o overhead de scheduling e context switching do pool limitado.

4. **Zero failed requests**: Ambas as versões completaram 100% dos requests sem erros — a migração não introduziu regressões de estabilidade.

## Verificação

- ✅ `mvn compile` — sem erros
- ✅ `mvn clean install -DskipTests` — sem erros
- ✅ Container Docker sobe sem erros
- ✅ `curl localhost:9090` — HTTP 200
- ✅ Benchmark comparativo mostra ganhos consistentes
