# Observabilidade — Zipkin / Brave

Documentação da camada de distributed tracing do n-gate.

## Stack

| Componente | Versão | Função |
|------------|--------|--------|
| [Brave](https://github.com/openzipkin/brave) | 6.0.3 | Instrumentação e criação de spans |
| [Zipkin Reporter](https://github.com/openzipkin/zipkin-reporter-java) | 3.4.0 | Envio assíncrono dos spans |
| `zipkin-sender-okhttp3` | 3.4.0 | Transporte via OkHttp para o collector |

O endpoint do Zipkin collector é resolvido via variável de ambiente `ZIPKIN_URL` ou propriedade de sistema `zipkin.url`.

---

## Arquitetura de Tracing

Cada request que entra no adapter gera um **trace completo** com múltiplos spans hierárquicos. O trace segue o padrão B3 para propagação de contexto entre o adapter e os backends.

```
Cliente
  │
  ▼
┌─────────────────────────────────────────────────┐
│ n-gate                                          │
│                                                 │
│  rootSpan (SERVER) ← extracted from B3 headers  │
│  ├── request-handler                            │
│  │   ├── dynamic-rules                          │
│  │   │   └── rules-execution (por script)       │
│  │   ├── upstream-request (CLIENT)              │
│  │   └── response-adapter                       │
│  │       ├── response-setup                     │
│  │       ├── response-processor (por closure)   │
│  │       ├── response-headers-copy              │
│  │       └── response-send-to-client            │
│  └── token-decoder (se endpoint secured)        │
└─────────────────────────────────────────────────┘
  │
  ▼ B3 headers injetados
Backend
```

---

## Spans — Referência Detalhada

### `any-handler-[<listener>]` — Root Span (SERVER)

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `EndpointWrapper.addServiceListener()` |
| **Kind** | `SERVER` |
| **Mede** | Ciclo completo do request no adapter |

**Tags semânticas:**
- `http.method` — Método HTTP (GET, POST, etc.)
- `http.url` — URL completa do request
- `http.path` — Path sem query string
- `http.query` — Query parameters
- `http.client_ip` — IP do cliente
- `http.status_code` — Status code da resposta
- `http.response_content_type` — Content-Type da resposta
- `listener` — Nome do listener Javalin

**Propagação B3:** Se o request de entrada contém headers B3 (`X-B3-TraceId`, `X-B3-SpanId`, etc.), o adapter **extrai o contexto** e cria o rootSpan como child do trace externo. Caso contrário, inicia um novo trace.

---

### `request-handler`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpProxyManager.handleRequest()` |
| **Parent** | rootSpan |
| **Mede** | Processamento completo do request (regras + upstream + response) |

**Tags:** `requestMethod`, todos os headers do request como `header-<name>`.

---

### `dynamic-rules`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpProxyManager.evalDynamicRules()` |
| **Parent** | request-handler |
| **Mede** | Setup do HttpWorkLoad + binding Groovy + execução de todos os scripts |

Contém child spans `rules-execution` para cada script executado.

---

### `rules-execution`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpProxyManager.evalDynamicRules()` (loop) |
| **Parent** | dynamic-rules |
| **Mede** | Execução de um script Groovy individual |

**Tags:** `script` — nome do arquivo `.groovy` executado.

**Nota:** O `GroovyScriptEngine` é configurado com `minimumRecompilationInterval = 60s` para evitar stat de filesystem a cada request. O tempo deste span (~600µs) é o piso irredutível do Groovy runtime (lookup + instância + execução).

---

### `upstream-request` (CLIENT)

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpProxyManager.handleRequest()` |
| **Kind** | `CLIENT` |
| **Parent** | request-handler |
| **Mede** | Chamada completa ao backend (DNS + TCP + TLS + request + response) |

**Tags:**
- `upstream-client-name` — Nome do backend configurado
- `upstream-req-url` — URL do request ao backend
- `upstream-req-method` — Método HTTP usado
- `upstream.status_code` — Status code retornado pelo backend
- `upstream.content_type` — Content-Type da resposta do backend

**Propagação B3:** Headers B3 são **injetados** no request para o backend, permitindo que o trace continue end-to-end se o backend também suportar tracing.

**Relevância de performance:** Este span geralmente domina o trace (~70% do tempo total), representado principalmente pela latência de rede + processamento do backend.

---

### `response-adapter`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpResponseAdapter.writeResponse()` |
| **Parent** | request-handler |
| **Mede** | Pipeline completo de envio da resposta ao cliente |

**Tags:**
- `return-pipe` — `true` se streaming direto, `false` se materializado em memória

Contém os seguintes child spans em sequência:

---

### `response-setup`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpResponseAdapter.writeResponse()` |
| **Parent** | response-adapter |
| **Mede** | Setup do client response + adição do header `x-trace-id` |

**Tags:** `synth` — `true` se é uma resposta sintética definida pelo script Groovy.

---

### `response-processor`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpResponseAdapter.writeResponse()` (loop) |
| **Parent** | response-adapter |
| **Mede** | Execução de uma closure Groovy de pós-processamento |

**Tags:** `processor-name` — nome do processor registrado via `workload.addResponseProcessor()`.

Haverá um span `response-processor` por closure registrada nos scripts Groovy.

---

### `response-headers-copy`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpResponseAdapter.writeResponse()` |
| **Parent** | response-adapter |
| **Mede** | Cópia de status + headers do upstream para o client response |

**Tags:**
- `status-code` — Status HTTP copiado
- `upstream-headers-count` — Número de headers copiados

---

### `response-send-to-client`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `HttpResponseAdapter.writeResponse()` |
| **Parent** | response-adapter |
| **Mede** | Transferência efetiva dos bytes ao cliente |

Quando `return-pipe = true`, streaming direto de `InputStream` → `OutputStream` (buffer 8KB).
Quando `return-pipe = false`, envio do `byte[]` materializado via `ctx.result()`.

---

### `token-decoder`

| Propriedade | Valor |
|-------------|-------|
| **Criado em** | `EndpointWrapper.getTokenDecoder()` |
| **Parent** | rootSpan |
| **Mede** | Decodificação e validação do JWT (endpoints secured) |

Só aparece em endpoints com autenticação habilitada.

---

## Header `x-trace-id`

Toda resposta do adapter inclui o header `x-trace-id` com o Trace ID do Zipkin. Isso permite **correlação direta** entre um request do cliente e o trace completo no Zipkin.

```bash
curl -v http://localhost:9090/api/resource
# < x-trace-id: f235a80ada773b83
```

Para consultar no Zipkin: `http://<zipkin>/zipkin/traces/f235a80ada773b83`

---

## Propagação B3

O adapter implementa propagação B3 bidirecional:

**Inbound (cliente → adapter):**
- Extrai `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-ParentSpanId`, `X-B3-Sampled` do request de entrada
- Se presentes, o rootSpan é criado como child do trace externo

**Outbound (adapter → backend):**
- Injeta headers B3 no request OkHttp para o backend
- Permite tracing end-to-end se o backend suportar B3

---

## Componentes Internos

### `TracerService`

Gerencia instâncias de `Tracing` e `Tracer` por service name. Cacheia instâncias via `ConcurrentHashMap`. O sender (`OkHttpSender`) e handler (`AsyncZipkinSpanHandler`) são inicializados uma única vez com double-checked locking.

### `TracerWrapper`

Wrapper sobre `Tracer` e `Tracing` que simplifica criação de spans. Métodos principais:
- `createSpan(name)` — cria span com parent automático
- `createChildSpan(name, parent)` — cria child span explícito
- `getTraceId()` — retorna o Trace ID em hexadecimal

### `SpanWrapper`

Wrapper sobre `Span` do Brave com API simplificada:
- `tag(key, value)` — adiciona tag (suporta String, int, long)
- `finish()` — finaliza o span
- `addError(exception)` — marca o span como erro
