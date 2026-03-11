# Regras Groovy — n-gate

O n-gate utiliza o **GroovyScriptEngine** como motor de regras dinâmicas. Scripts `.groovy` no diretório `rules/` são executados a cada request, permitindo modificar roteamento, headers, payloads e gerar respostas programáticas.

---

## Como Funciona

1. O `adapter.yaml` define o script Groovy para cada listener/contexto via `ruleMapping`
2. A cada request, o `HttpProxyManager` executa o script com um **binding** contendo variáveis do contexto
3. O script pode modificar o request, selecionar backends, criar respostas sintéticas e registrar processors
4. O GroovyScriptEngine faz **hot-reload**: recompila scripts automaticamente a cada **60 segundos** (configurável)

---

## Variáveis Disponíveis no Binding

| Variável | Tipo | Descrição |
|----------|------|-----------|
| `workload` | `HttpWorkLoad` | Objeto principal — carrega request, response e configuração do pipeline |
| `context` | `CustomContextWrapper` | Wrapper do contexto Javalin (acesso a headers, params, body) |
| `upstreamRequest` | `HttpAdapterServletRequest` | Request que será enviado ao backend (modificável) |
| `utils` | `Map<String, Object>` | Utilitários: `gson`, `json`, `xml`, `httpClient` |
| `listener` | `String` | Nome do listener que recebeu o request |
| `contextName` | `String` | Nome do URL Context ativo |
| `requestMethod` | `String` | Método HTTP do contexto |
| `include` | `String` | Próximo script a executar (cadeia de scripts) |

---

## API do HttpWorkLoad

### Propriedades

| Propriedade | Tipo | Default | Descrição |
|-------------|------|---------|-----------|
| `returnPipe` | `Boolean` | `true` | `true` = streaming direto, `false` = materializa body em memória |
| `body` | `String` | `""` | Body do request (leitura/escrita) |
| `objects` | `ConcurrentMap<String, Object>` | vazio | Map para compartilhar dados entre scripts e processors |

### Métodos

| Método | Retorno | Descrição |
|--------|---------|-----------|
| `createSynthResponse()` | `SyntHttpResponse` | Cria resposta sintética (bypass do backend) |
| `addResponseProcessor(name, closure)` | `void` | Registra closure de pós-processamento |
| `addObject(name, obj)` | `void` | Adiciona objeto ao map compartilhado |
| `getRequest()` | `HttpAdapterServletRequest` | Request HTTP adaptado |
| `getContext()` | `CustomContextWrapper` | Contexto Javalin |
| `getUpstreamResponse()` | `SyntHttpResponse` | Resposta do backend (disponível nos processors) |
| `clientResponse()` | `HttpAdapterServletResponse` | Resposta que será enviada ao cliente |

---

## API do HttpAdapterServletRequest

O `upstreamRequest` permite modificar o request antes de enviá-lo ao backend:

| Método | Descrição |
|--------|-----------|
| `setRequestURI(uri)` | Altera o path do request upstream |
| `setQueryString(qs)` | Define a query string |
| `setBackend(name)` | Muda o backend de destino (chave do `adapter.yaml`) |
| `getBackend()` | Retorna o backend atual |
| `getRequestURI()` | Retorna o path atual |
| `addHeader(name, value)` | Adiciona ou substitui um header no request upstream |
| `getHeader(name)` | Retorna o valor de um header |
| `setContentType(type)` | Altera o `Content-Type` do request |
| `getBodyAsBytes()` | Retorna o body original do cliente como `byte[]` (somente leitura) |

> [!IMPORTANT]
> **Modificação do body do upstream request** não é suportada na implementação atual. O `HttpAdapterServletRequest` faz lazy loading do body original do cliente via `getBodyAsBytes()`, mas **não expõe um setter** para alterá-lo. Apenas headers, path, query string e backend podem ser modificados. Para cenários que exigem transformação de body, a alternativa é interceptar a resposta via Response Processor ou usar `utils.httpClient` para fazer uma chamada manual ao backend com body customizado e retornar como resposta sintética.

---

## API do CustomContextWrapper (Response)

O `context` também permite manipular a resposta diretamente para o cliente:

| Método | Descrição |
|--------|-----------|
| `header(name, value)` | Define um header no response ao cliente |
| `removeHeader(name)` | Remove um header do response |
| `status(code)` | Define o status HTTP do response (int ou `HttpStatus`) |
| `contentType(type)` | Define o `Content-Type` do response |
| `result(string)` | Define o body do response como string |
| `result(bytes)` | Define o body do response como byte array |
| `json(obj)` | Serializa objeto como JSON e define no response |
| `html(html)` | Define o body como HTML |
| `redirect(url)` | Redireciona o cliente para outra URL |
| `redirect(url, status)` | Redireciona com status HTTP específico |
| `cookie(name, value)` | Define um cookie no response |
| `cookie(name, value, maxAge)` | Define um cookie com tempo de expiração |
| `removeCookie(name)` | Remove um cookie |
| `raiseException()` | Lança exceção genérica (aborta o request) |
| `raiseException(msg)` | Lança exceção com mensagem customizada |

---

## API do HttpAdapterServletResponse (Client Response)

O `workload.clientResponse()` é um wrapper do `HttpServletResponse` e permite manipular headers da resposta **dentro de Response Processors**:

| Método | Descrição |
|--------|-----------|
| `addHeader(name, value)` | Adiciona um header ao response do cliente |
| `setHeader(name, value)` | Define (sobrescreve) um header no response |
| `setStatus(code)` | Define o status HTTP do response |
| `addIntHeader(name, value)` | Adiciona um header com valor inteiro |
| `setContentType(type)` | Define o `Content-Type` |

> **Nota:** Este objeto é utilizado primariamente dentro de Response Processors, onde o response do upstream já está disponível.

---

## Utilitários (`utils`)

| Chave | Tipo | Descrição |
|-------|------|-----------|
| `utils.gson` | `Gson` | Serialização/deserialização JSON (Google Gson) |
| `utils.json` | `JsonSlurper` | Parser JSON nativo do Groovy |
| `utils.xml` | `XmlSlurper` | Parser XML nativo do Groovy |
| `utils.httpClient` | `HttpClientUtils` | Utilitário para criar HTTP clients para chamadas secundárias |

### HttpClientUtils

| Método | Descrição |
|--------|-----------|
| `getAssyncBackend(name)` | Cria um client HTTP assíncrono para um backend nomeado |

---

## Cadeia de Scripts (`include`)

Scripts podem encadear a execução de outros scripts:

```groovy
// Rules.groovy
include = "default/PostProcess.groovy"

// O engine vai executar PostProcess.groovy após este script
```

O loop continua enquanto `include` não for vazio. Para interromper, não defina `include` ou defina como `""`.

---

## Exemplos Práticos

### 1. Roteamento Dinâmico por Path

Seleciona o backend com base no path do request:

```groovy
// rules/default/Rules.groovy

def path = context.path()

if (path.startsWith("/api/users")) {
    upstreamRequest.setBackend("users-service")
} else if (path.startsWith("/api/products")) {
    upstreamRequest.setBackend("products-service")
} else if (path.startsWith("/api/orders")) {
    upstreamRequest.setBackend("orders-service")
}
```

**Pré-requisito:** Backends `users-service`, `products-service` e `orders-service` devem estar definidos no `adapter.yaml`.

---

### 2. Resposta Sintética (Mock/Stub)

Gera uma resposta sem chamar nenhum backend — ideal para dev/test:

```groovy
// rules/default/Rules.groovy

def path = context.path()

if (path == "/health") {
    def synth = workload.createSynthResponse()
    synth.setContent('{"status": "UP", "service": "n-gate"}')
    synth.setStatus(200)
    synth.addHeader("Content-Type", "application/json")
}
```

Resultado:
```bash
curl http://localhost:9090/health
# {"status": "UP", "service": "n-gate"}
```

---

### 3. Injeção de Headers no Request Upstream

Adiciona headers ao request que será enviado ao backend:

```groovy
// rules/default/Rules.groovy

// Adiciona header de correlação
upstreamRequest.addHeader("X-Correlation-Id", java.util.UUID.randomUUID().toString())

// Adiciona header com o listener de origem
upstreamRequest.addHeader("X-Source-Listener", listener)

// Copia headers específicos do cliente
def clientToken = context.header("X-Custom-Token")
if (clientToken != null) {
    upstreamRequest.addHeader("X-Forwarded-Token", clientToken)
}
```

---

### 4. Headers no Response ao Cliente

Adiciona headers customizados na resposta que o cliente receberá, utilizando um **Response Processor** com `wl.clientResponse.addHeader()`.

> [!IMPORTANT]
> O método correto para injetar headers no response ao cliente é via **Response Processor**, usando `wl.clientResponse.addHeader()`. Essa é a abordagem validada nos testes do projeto e funciona em todos os cenários (streaming, materializado e sintético). Evite `context.header()` para este propósito — ele opera na camada Javalin e pode não sobreviver ao pipeline de proxy em todos os modos de resposta.

```groovy
// rules/default/Rules.groovy

def addResponseHeaders = { wl ->
    // Headers estáticos — sempre presentes no response
    wl.clientResponse.addHeader("X-Powered-By", "n-gate")
    wl.clientResponse.addHeader("X-Request-Id", java.util.UUID.randomUUID().toString())

    // Propaga headers seletivos do upstream para o cliente
    def backendVersion = wl.upstreamResponse?.getHeader("X-Backend-Version")
    if (backendVersion) {
        wl.clientResponse.addHeader("X-Backend-Version", backendVersion)
    }

    // Adiciona headers de segurança
    wl.clientResponse.addHeader("X-Content-Type-Options", "nosniff")
    wl.clientResponse.addHeader("X-Frame-Options", "DENY")
    wl.clientResponse.addHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")

    // Header dinâmico baseado no status do upstream
    def status = wl.upstreamResponse?.getStatus()
    wl.clientResponse.addHeader("X-Upstream-Status", String.valueOf(status))
}

workload.addResponseProcessor('addResponseHeaders', addResponseHeaders)
```

Resultado:
```bash
curl -v http://localhost:9090/api/resource
# < X-Powered-By: n-gate
# < X-Request-Id: 550e8400-e29b-41d4-a716-446655440000
# < X-Content-Type-Options: nosniff
# < X-Frame-Options: DENY
# < X-Upstream-Status: 200
```

---

### 5. Response Processor — Streaming Condicional

Materializa o body apenas para respostas pequenas, faz streaming para grandes:

```groovy
// rules/default/Rules.groovy

def binaryDataProcessor = { wl ->
    def contentSize = wl.upstreamResponse.getHeader("Content-Length")
    if (contentSize) {
        contentSize = contentSize.toLong()
        if (contentSize > 100000) {
            wl.clientResponse.addHeader('x-big', 'yes')
            wl.returnPipe = true
        } else {
            wl.clientResponse.addHeader('x-big', 'no')
            wl.returnPipe = false
        }
    }

    // Streaming automático para imagens
    def contentType = wl.upstreamResponse.getHeader("Content-Type")
    if (contentType?.contains("image")) {
        wl.returnPipe = true
        wl.clientResponse.addHeader('x-content-type', contentType)
    }
}

workload.addResponseProcessor('binaryDataProcessor', binaryDataProcessor)
```

> **⚠️ Nota:** Response processors são executados no hot path. Use com sabedoria — cada processor adiciona latência.

---

### 6. Chamada Assíncrona a Backend Secundário

Chama outro backend e armazena o resultado para uso posterior:

```groovy
// rules/default/Rules.groovy

// Cria um client para o backend secundário
def secondaryBe = utils.httpClient.getAssyncBackend("metadata-service")

// Faz uma chamada GET assíncrona
def future = secondaryBe.get("http://metadata-service:8080/metadata/" + context.pathParam("id"), [:])

// Aguarda a resposta
def response = future.join()
workload.addObject("metadata", response.body().string())
```

---

### 7. Resposta Sintética com Composição de Dados

Combina dados de múltiplas fontes em uma resposta única:

```groovy
// rules/default/Rules.groovy

if (context.path() == "/api/dashboard") {
    // Dados do usuário (do token JWT)
    def userPrincipal = workload.objects.get("USER_PRINCIPAL")

    // Cria resposta composta
    def dashboard = [
        user: userPrincipal?.id,
        timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss"),
        service: listener
    ]

    def synth = workload.createSynthResponse()
    synth.setContent(utils.gson.toJson(dashboard))
    synth.setStatus(200)
    synth.addHeader("Content-Type", "application/json")
}
```

---

### 8. Rewrite de URL

Modifica o path/query antes de encaminhar ao backend:

```groovy
// rules/default/Rules.groovy

def path = context.path()

// Remove prefixo do gateway
if (path.startsWith("/gateway/v1")) {
    upstreamRequest.setRequestURI(path.replace("/gateway/v1", ""))
}

// Adiciona parâmetros de query
def existingQs = context.queryString() ?: ""
upstreamRequest.setQueryString(existingQs + "&source=n-gate")
```

---

### 9. Manipulação de Cookies

Define e remove cookies no response ao cliente:

```groovy
// rules/default/Rules.groovy

// Define cookie de sessão
context.cookie("SESSION_ID", java.util.UUID.randomUUID().toString(), 3600)

// Cookie para tracking de versão
context.cookie("app-version", "2.1.1")

// Remove cookie legado
context.removeCookie("old-session")
```

---

### 10. Redirecionamento Condicional

Redireciona o cliente com base em condições de roteamento:

```groovy
// rules/default/Rules.groovy

def path = context.path()

// Redireciona versões antigas da API
if (path.startsWith("/api/v1/")) {
    def newPath = path.replace("/api/v1/", "/api/v2/")
    context.redirect("https://api.example.com" + newPath, io.javalin.http.HttpStatus.MOVED_PERMANENTLY)
    return
}

// Redireciona para HTTPS se veio por HTTP
if (context.scheme() == "http" && context.header("X-Forwarded-Proto") != "https") {
    context.redirect("https://" + context.host() + context.fullUrl())
    return
}
```

---

### 11. Tratamento de Erros e Abort

Interrompe o processamento do request com erro:

```groovy
// rules/default/Rules.groovy

// Valida header obrigatório
def apiKey = context.header("X-API-Key")
if (apiKey == null || apiKey.isEmpty()) {
    def synth = workload.createSynthResponse()
    synth.setContent('{"error": "Missing X-API-Key header"}')
    synth.setStatus(401)
    synth.addHeader("Content-Type", "application/json")
    return
}

// Bloqueia IPs específicos
def clientIp = context.ip()
def blockedIps = ["10.0.0.100", "192.168.1.50"]
if (blockedIps.contains(clientIp)) {
    def synth = workload.createSynthResponse()
    synth.setContent('{"error": "Forbidden"}')
    synth.setStatus(403)
    synth.addHeader("Content-Type", "application/json")
    return
}

// Aborta com exceção (retorna 500 ao cliente)
if (context.header("X-Force-Error") != null) {
    context.raiseException("Forced error for testing")
}
```

---

### 12. Transformação do Body do Response

Modifica o conteúdo da resposta antes de entregar ao cliente:

```groovy
// rules/default/Rules.groovy

// Desabilita streaming para poder ler/modificar o body
workload.returnPipe = false

def transformBody = { wl ->
    def body = wl.body

    if (body && wl.upstreamResponse?.getHeader("Content-Type")?.contains("application/json")) {
        // Parse o JSON do backend
        def json = new groovy.json.JsonSlurper().parseText(body)

        // Enriquece com metadados do gateway
        json.gateway = [
            processedBy: "n-gate",
            listener: wl.context.getContextName(),
            timestamp: System.currentTimeMillis()
        ]

        // Serializa de volta
        wl.body = new groovy.json.JsonBuilder(json).toString()
        wl.clientResponse.setHeader("Content-Type", "application/json; charset=utf-8")
    }
}

workload.addResponseProcessor('transformBody', transformBody)
```

---

## Boas Práticas

1. **Mantenha scripts leves** — O script é executado a cada request. Evite operações pesadas como I/O de disco ou computação complexa.
2. **Use `returnPipe = true`** (padrão) — Streaming é mais performático. Só desabilite quando precisar inspecionar/modificar o body.
3. **Response Processors são custosos** — Cada processor adiciona overhead no hot path. Use apenas quando necessário.
4. **Recompilação** — Scripts são recompilados automaticamente a cada 60 segundos. Para testar mudanças, aguarde o intervalo ou reinicie o gateway.
5. **Isolamento** — Use `ProtectedBinding` (automático) para evitar vazamento de estado entre requests concorrentes.
6. **Encadeamento** — Use `include` para modularizar regras complexas em múltiplos scripts.
7. **Headers no response** — Para headers estáticos, use `context.header()` direto no script. Para headers que dependem da resposta do backend, use Response Processors com `wl.clientResponse.addHeader()`.
8. **Abort com resposta customizada** — Prefira `createSynthResponse()` com status/body explícitos em vez de `raiseException()`, para retornar mensagens de erro estruturadas ao cliente.
