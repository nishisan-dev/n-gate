# Segurança e Políticas — n-gate

O n-gate implementa um modelo de segurança em camadas que cobre tanto a **validação de requests de entrada** (JWT) quanto a **autenticação automática nos backends** (OAuth2).

---

## Modelo de Segurança

```
Cliente                n-gate                         Backend
  │                      │                               │
  │ ── Request + JWT ──▶ │                               │
  │                      │── Validate JWT (JWKS) ──┐     │
  │                      │                         │     │
  │                      │◀── Token válido ────────┘     │
  │                      │                               │
  │                      │── Obtain OAuth2 Token ──┐     │
  │                      │                         │     │
  │                      │◀── Bearer token ────────┘     │
  │                      │                               │
  │                      │── Request + Bearer ──────────▶ │
  │                      │                               │
  │                      │◀── Response ─────────────────  │
  │ ◀── Response ──────  │                               │
```

### Duas Dimensões de Segurança

1. **Inbound (Cliente → n-gate):** Validação de JWT no request de entrada
2. **Outbound (n-gate → Backend):** Injeção automática de token OAuth2

Estas duas dimensões são **independentes**:
- Um listener pode ser `secured: true` (exige JWT do cliente) e seu backend pode ter ou não `oauthClientConfig`
- Um listener pode ser `secured: false` e o backend ainda assim usar OAuth2 para se autenticar

---

## Autenticação de Entrada (JWT)

### Configuração

Para habilitar validação JWT em um listener, defina `secured: true` e configure o `secureProvider`:

```yaml
listeners:
  api:
    listenPort: 9090
    secured: true
    secureProvider:
      providerClass: "dev.nishisan.ngate.auth.jwt.JWTTokenDecoder"
      name: "my-jwt-decoder"
      options:
        issuerUri: http://keycloak:8080/realms/my-realm
        jwkSetUri: http://keycloak:8080/realms/my-realm/protocol/openid-connect/certs
```

### Como Funciona

1. O `EndpointWrapper` intercepta o request antes de chamar o proxy
2. O `TokenDecoder` extrai o header `Authorization: Bearer <token>`
3. O JWT é validado contra o JWKS endpoint (chaves públicas do Identity Provider)
4. Se válido, o `IAuthUserPrincipal` é disponibilizado no contexto como `USER_PRINCIPAL`
5. Se inválido, retorna `HTTP 401 Unauthorized` com header `x-trace-id`

### Cache de Decoder

- Decoders são cacheados por `name` (chave do `secureProvider`)
- O cache tem uma data de expiração configurável
- Ao expirar, o decoder é recriado automaticamente (recarrega JWKS)

### Acesso ao Principal no Groovy

```groovy
// No script Groovy, o USER_PRINCIPAL está disponível se o endpoint é secured
def principal = workload.objects.get("USER_PRINCIPAL")
if (principal != null) {
    println("User ID: " + principal.id)
}
```

---

## Políticas de Segurança por Contexto

O n-gate permite configuração granular de segurança **por URL Context**, indo além da configuração global do listener:

```yaml
listeners:
  api:
    listenPort: 9090
    secured: true
    secureProvider:
      providerClass: "dev.nishisan.ngate.auth.jwt.JWTTokenDecoder"
      name: "jwt-decoder"
      options:
        issuerUri: http://keycloak:8080/realms/my-realm
        jwkSetUri: http://keycloak:8080/realms/my-realm/protocol/openid-connect/certs
    urlContexts:
      # Protegido — requer JWT
      protected-api:
        context: "/api/*"
        method: "ANY"
        secured: true
        ruleMapping: "default/Rules.groovy"

      # Aberto — não requer JWT (health check, status)
      health:
        context: "/health"
        method: "GET"
        secured: false

      # Aberto — webhook callback (recebe de terceiros)
      webhook:
        context: "/webhook/*"
        method: "POST"
        secured: false
```

### Regras de Precedência

1. Se `listener.secured = false` → Nenhum contexto é protegido (override global)
2. Se `listener.secured = true` e `urlContext.secured` não definido → Herda `true` do listener
3. Se `listener.secured = true` e `urlContext.secured = false` → Este contexto específico é aberto

---

## Autenticação de Saída (OAuth2)

### Configuração

Para que o n-gate injete automaticamente tokens OAuth2 nos requests ao backend:

```yaml
backends:
  my-api:
    backendName: "my-api"
    endPointUrl: "https://api.example.com"
    oauthClientConfig:
      ssoName: "my-sso"
      clientId: "gateway-client"
      clientSecret: "super-secret"
      userName: "svc-account"
      password: "svc-password"
      tokenServerUrl: "http://keycloak:8080/realms/my-realm/protocol/openid-connect/token"
      useRefreshToken: true
      renewBeforeSecs: 30
      authScopes:
        - "openid"
        - "profile"
        - "email"
```

### Como Funciona

1. O `OAuthClientManager` obtém um access token via **Resource Owner Password Grant**
2. O token é cacheado e associado ao `ssoName`
3. A cada request, o interceptor OkHttp injeta `Authorization: Bearer <token>` no header
4. Quando o token está a `renewBeforeSecs` segundos de expirar, é renovado proativamente
5. Se `useRefreshToken: true`, a renovação usa o refresh token em vez de re-autenticar

### Fluxo de Token

```
n-gate                           Keycloak
  │                                  │
  │── POST /token ──────────────────▶│
  │   grant_type=password            │
  │   client_id + client_secret      │
  │   username + password            │
  │   scope=openid profile email     │
  │                                  │
  │◀── access_token + refresh_token ─│
  │                                  │
  │   [cache: ssoName → token]       │
  │                                  │
  │── Request ao Backend ──────────▶ │
  │   Authorization: Bearer <token>  │
```

### Mascaramento nos Logs

O n-gate mascara automaticamente tokens sensíveis nos logs:
- Headers `Authorization` são logados como `Bearer ***`
- A verificação é feita com `header.equalsIgnoreCase("Authorization")`

---

## Token Decoder Customizado (Groovy)

Para cenários onde o `JWTTokenDecoder` built-in não é suficiente, é possível criar um decoder customizado via script Groovy:

### Configuração

```yaml
secureProvider:
  providerClass: "custom/MyDecoder.groovy"
  name: "custom-decoder"
  options:
    apiKey: "my-api-key"
    endpoint: "http://auth-service:3000/validate"
```

### Script Groovy

Crie o arquivo `custom/MyDecoder.groovy`:

```groovy
// custom/MyDecoder.groovy

import dev.nishisan.ngate.auth.CustomUserPrincipal

// Intervalo de recriação do decoder (segundos)
decoder.decoderRecreateInterval = 3600

// Closure de inicialização (executada uma vez)
decoder.initClosure = {
    println("Custom decoder initialized!")
}

// Closure de decodificação (executada a cada request)
decoder.decodeTokenClosure = { ctx ->
    def authHeader = ctx.header("Authorization")

    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        throw new dev.nishisan.ngate.exception.TokenDecodeException("Missing token")
    }

    def token = authHeader.replace("Bearer ", "")

    // Validação customizada (ex: chamar serviço externo, consultar banco, etc.)
    // ...

    // Retorna o principal
    def principal = new CustomUserPrincipal()
    principal.id = "user-123"
    return principal
}
```

### Campos do `decoder`

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `initClosure` | `Closure` | Executada uma vez ao criar o decoder |
| `decodeTokenClosure` | `Closure<IAuthUserPrincipal>` | Executada a cada request — recebe o contexto e retorna o principal |
| `decoderRecreateInterval` | `int` | Segundos até recriar o decoder (0 = nunca recriar) |

---

## SSL/TLS

### Backend (Outbound)

Por padrão, o n-gate usa um Trust Manager permissivo (`TRUST_ALL_MANAGER`) para conexões com backends. Isso significa:
- Aceita qualquer certificado SSL do backend
- Não valida hostname

> **⚠️ Atenção:** Esta configuração é adequada para ambientes de desenvolvimento. Em produção, considere configurar certificados específicos.

### Listener (Inbound)

Para habilitar HTTPS no listener:

```yaml
listeners:
  https:
    listenPort: 8443
    ssl: true
```

Requer keystore configurado em `ssl/`. Consulte a documentação do Javalin SSL Plugin para detalhes sobre formatos suportados (JKS, PKCS12, PEM).

---

## Resumo de Políticas

| Aspecto | Configuração | Onde |
|---------|-------------|------|
| JWT obrigatório na entrada | `listener.secured: true` | `adapter.yaml` → listeners |
| JWT por rota | `urlContext.secured: true/false` | `adapter.yaml` → urlContexts |
| OAuth2 no upstream | `backend.oauthClientConfig` | `adapter.yaml` → backends |
| Decoder customizado | `secureProvider.providerClass` | `adapter.yaml` + script Groovy |
| Token auto-refresh | `useRefreshToken: true` | `adapter.yaml` → oauthClientConfig |
| Renovação proativa | `renewBeforeSecs: 30` | `adapter.yaml` → oauthClientConfig |
| Mascaramento de token | Automático | Código (interceptor OkHttp) |
