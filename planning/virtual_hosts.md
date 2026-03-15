# Virtual Hosts — Mapping serverName → backend no Listener

## Contexto

Atualmente o n-gate resolve o backend de destino na seguinte ordem:
1. **Groovy rules** (`workload.getRequest().setBackend(...)`) — se o script define o backend
2. **`defaultBackend`** do listener — fallback quando nenhum script define

Não existe conceito de roteamento baseado em hostname. O objetivo é adicionar um **mapping `serverName → backend`** dentro do listener, de forma que o header `Host` determine o backend **antes** do fallback para `defaultBackend`.

## Abordagem

Modelo simples dentro do listener:

```yaml
listeners:
  http:
    listenPort: 8080
    defaultBackend: "fallback-api"       # fallback quando Host não casa
    virtualHosts:                         # NEW: mapping serverName → backend
      "api.example.com": "api-backend"
      "admin.example.com": "admin-backend"
      "*.staging.example.com": "staging-backend"
```

**Ordem de resolução de backend:**
1. Groovy rules (`request.setBackend(...)`) — maior prioridade (inalterado)
2. **Virtual hosts** — match do header `Host` contra `virtualHosts`
3. `defaultBackend` do listener — fallback final

> [!IMPORTANT]
> **Backward-compatible**: se `virtualHosts` não for definido, o comportamento é 100% idêntico ao atual. Zero breaking changes.

---

## Proposed Changes

### Componente 1 — Modelo de Configuração

#### [MODIFY] [EndPointListenersConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/EndPointListenersConfiguration.java)

Adicionar campo:

```java
private Map<String, String> virtualHosts = new LinkedHashMap<>();  // serverName → backendName
```

Com getter/setter. Jackson desserializa automaticamente do YAML.

---

### Componente 2 — Virtual Host Resolver

#### [NEW] [VirtualHostResolver.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/VirtualHostResolver.java)

Classe responsável por resolver o hostname contra o mapa de virtual hosts:

```java
public class VirtualHostResolver {
    /**
     * Resolve o backend name a partir do Host header e do mapa de virtualHosts.
     * Retorna Optional.empty() se nenhum match — caller usa defaultBackend.
     *
     * Ordem de matching:
     *   1. Exact match: "api.example.com"
     *   2. Wildcard:    "*.example.com" (mais longo primeiro)
     */
    public static Optional<String> resolve(String hostHeader, Map<String, String> virtualHosts);
}
```

Lógica:
- Normaliza o `Host` header (strip porta, lowercase)
- Tenta exact match primeiro
- Se não encontra, tenta wildcard matches (`*.example.com`) por substring
- Se nenhum match → retorna `empty()` → `defaultBackend` do listener é usado

---

### Componente 3 — Integração no Pipeline de Request

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/HttpProxyManager.java)

No método `handleRequest()`, na seção de resolução de backend (~linhas 538-553), adicionar a checagem de virtual hosts **entre** o check do Groovy rules e o `defaultBackend`:

```diff
 // Se o script Groovy definiu backend, usa ele
 if (w.getRequest().getBackend() != null && !w.getRequest().getBackend().trim().equals("")) {
     backendname = w.getRequest().getBackend().trim();
+} else if (endPointConfiguration.getVirtualHosts() != null 
+           && !endPointConfiguration.getVirtualHosts().isEmpty()) {
+    // Virtual host: resolve pelo Host header
+    String hostHeader = handler.header("Host");
+    Optional<String> vhostBackend = VirtualHostResolver.resolve(
+        hostHeader, endPointConfiguration.getVirtualHosts());
+    if (vhostBackend.isPresent()) {
+        backendname = vhostBackend.get();
+        logger.debug("Virtual host matched: Host=[{}] → Backend=[{}]", hostHeader, backendname);
+    } else if (endPointConfiguration.getDefaultBackend() != null) {
+        backendname = endPointConfiguration.getDefaultBackend().trim();
+    }
 } else if (endPointConfiguration.getDefaultBackend() != null) {
     ...
 }
```

#### [MODIFY] [EndpointWrapper.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/EndpointWrapper.java)

Adicionar tag de tracing `vhost.match` no span quando virtual host resolver encontra match. Isso dá visibilidade no Zipkin de qual vhost foi selecionado.

---

### Componente 4 — Observabilidade

#### [MODIFY] [EndpointWrapper.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/EndpointWrapper.java)

Adicionar tag `http.host` no root span com o valor do header `Host`, se presente:

```java
rootSpan.tag("http.host", ctx.header("Host"));
```

---

### Componente 5 — Documentação

#### [MODIFY] [configuration.md](file:///home/lucas/Projects/n-gate/docs/configuration.md)

Adicionar campo `virtualHosts` na tabela de campos do listener e seção dedicada com exemplos.

---

## Configuração YAML — Exemplo Completo

```yaml
endpoints:
  default:
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 8080
        ssl: false
        defaultBackend: "fallback-api"         # usado se Host não casa
        secured: false
        virtualHosts:                           # mapping serverName → backend
          "api.example.com": "api-backend"
          "api.staging.example.com": "api-backend"
          "admin.example.com": "admin-backend"
          "*.cdn.example.com": "cdn-backend"
        urlContexts:
          default:
            context: "/*"
            method: "ANY"
            ruleMapping: "default/Rules.groovy"

    backends:
      api-backend:
        backendName: "api-backend"
        members:
          - url: "http://api-server:3000"
      admin-backend:
        backendName: "admin-backend"
        members:
          - url: "http://admin-server:3001"
      cdn-backend:
        backendName: "cdn-backend"
        members:
          - url: "http://cdn-server:3002"
      fallback-api:
        backendName: "fallback-api"
        members:
          - url: "http://default-server:3003"
```

**Comportamento:**
- `curl -H "Host: api.example.com" http://localhost:8080/users` → **api-backend**
- `curl -H "Host: admin.example.com" http://localhost:8080/dashboard` → **admin-backend**
- `curl -H "Host: test.cdn.example.com" http://localhost:8080/assets` → **cdn-backend** (wildcard)
- `curl -H "Host: unknown.com" http://localhost:8080/` → **fallback-api** (defaultBackend)

---

## Verificação

### Testes Automatizados

#### 1. `VirtualHostResolverTest` — [NEW]

| # | Cenário | Input | Esperado |
|---|---------|-------|----------|
| T1 | Exact match | `"api.example.com"` | `Optional.of("api-backend")` |
| T2 | Exact match (case-insensitive) | `"API.Example.COM"` | `Optional.of("api-backend")` |
| T3 | Host com porta | `"api.example.com:8080"` | `Optional.of("api-backend")` |
| T4 | Wildcard match | `"test.cdn.example.com"` | `Optional.of("cdn-backend")` |
| T5 | No match → empty | `"unknown.com"` | `Optional.empty()` |
| T6 | Null host → empty | `null` | `Optional.empty()` |
| T7 | Mapa vazio → empty | `"api.example.com"` | `Optional.empty()` |

```bash
mvn test -pl . -Dtest="VirtualHostResolverTest" -DfailIfNoTests=false
```

#### 2. `ConfigurationManagerTest` — [MODIFY]

T5: YAML com `virtualHosts` é parseado corretamente.

#### 3. Regressão

```bash
mvn test -pl . -DfailIfNoTests=false
```

### Verificação Manual

```bash
curl -H "Host: api.example.com" http://localhost:8080/test
curl -H "Host: admin.example.com" http://localhost:8080/test
curl -H "Host: unknown.com" http://localhost:8080/test
```

---

## Sequência de Implementação

| Ordem | Componente | Arquivo | Tipo |
|-------|-----------|---------|------|
| 1 | Config model | `EndPointListenersConfiguration.java` | MODIFY |
| 2 | Resolver | `VirtualHostResolver.java` | NEW |
| 3 | Pipeline | `HttpProxyManager.java` | MODIFY |
| 4 | Observability | `EndpointWrapper.java` | MODIFY |
| 5 | Teste: Resolver | `VirtualHostResolverTest.java` | NEW |
| 6 | Teste: Config | `ConfigurationManagerTest.java` | MODIFY |
| 7 | Docs | `configuration.md` | MODIFY |

> Branch: `feature/virtual-hosts` a partir de `main`
