# Plano de Documentação — n-gate

## Contexto

O **n-gate** é um API Gateway/Reverse Proxy de alta performance construído em **Java 21** com **Javalin 7** (Jetty 12), **OkHttp 4** e um motor de regras dinâmicas em **Groovy 3**. Possui observabilidade integrada via **Brave/Zipkin**, autenticação OAuth2/JWT, e logging assíncrono via **LMAX Disruptor**.

O `README.md` atual cobre apenas o setup do ambiente Docker local. Falta documentação de:
- Arquitetura e fluxo interno
- Referência completa do `adapter.yaml`
- Como escrever/usar regras Groovy
- Políticas de segurança e configuração OAuth/JWT
- Casos de uso do mundo real
- Diagramas técnicos

---

## Alterações Propostas

### 1. README.md (Reescrita)

#### [MODIFY] [README.md](file:///home/lucas/Projects/n-gate/README.md)

Transformar de um guia "Dev Local" para documentação principal do projeto:

- **Header**: Nome, descrição concisa, badges (Java 21, License GPL-3.0)
- **O que é o n-gate**: Parágrafo curto posicionando o projeto
- **Features**: Lista das capacidades-chave
- **Arquitetura em alto nível**: Diagrama PlantUML inline + breve explicação
- **Quick Start**: O setup Docker atual (preservado, revisado)
- **Documentação**: Links para os guias em `docs/`
- **Tech Stack**: Tabela com componentes/versões
- **Licença**: GPL-3.0

---

### 2. Guia de Arquitetura

#### [NEW] [architecture.md](file:///home/lucas/Projects/n-gate/docs/architecture.md)

- Visão geral da arquitetura (componentes principais)
- Fluxo de um request (listener → regras → upstream → response)
- Componentes Java (pacotes e responsabilidades)
- Modelo de threading (Jetty threads + Virtual Threads no OkHttp Dispatcher)
- Streaming vs materialização (returnPipe)
- Diagrama PlantUML referenciando `docs/diagrams/architecture.puml`

---

### 3. Referência de Configuração

#### [NEW] [configuration.md](file:///home/lucas/Projects/n-gate/docs/configuration.md)

Documentar **todas** as chaves do `adapter.yaml` com:
- Descrição, tipo, valor padrão
- Exemplos práticos para cada seção:
  - `endpoints.default.listeners` — Listeners HTTP/HTTPS
  - `endpoints.default.backends` — Configuração de backends
  - `oauthClientConfig` — Credenciais e tokens
  - Tuning: `jettyMinThreads`, `connectionPoolSize`, `dispatcherMaxRequests`, etc.
- Variáveis de ambiente suportadas (`NGATE_CONFIG`, `ZIPKIN_ENDPOINT`, `TRACING_ENABLED`, `SPRING_PROFILES_DEFAULT`)
- Exemplo mínimo completo
- Exemplo avançado com múltiplos listeners, SSL e múltiplos backends

---

### 4. Guia de Regras Groovy

#### [NEW] [groovy_rules.md](file:///home/lucas/Projects/n-gate/docs/groovy_rules.md)

- Como funciona o motor de regras (GroovyScriptEngine, recompilação de 60s)
- Variáveis disponíveis no binding: `workload`, `context`, `utils`, `upstreamRequest`, `listener`, `contextName`
- API do `HttpWorkLoad` (returnPipe, createSynthResponse, addResponseProcessor)
- API do `HttpAdapterServletRequest` (setBackend, setRequestURI, setQueryString)
- Utilitários (`utils.gson`, `utils.json`, `utils.xml`, `utils.httpClient`)
- **Exemplos práticos**:
  1. Roteamento dinâmico por path
  2. Resposta sintética (mock)
  3. Response Processor para streaming condicional
  4. Chamar backend secundário de forma assíncrona
  5. Injeção de headers customizados
- Cadeia de scripts (`include`)

---

### 5. Documentação de Segurança e Políticas

#### [NEW] [security.md](file:///home/lucas/Projects/n-gate/docs/security.md)

- Modelo de segurança do n-gate (camadas de autenticação)
- Configuração de endpoints secured vs não-secured
- JWT Token Decoder (built-in `JWTTokenDecoder`)
  - Configuração do `secureProvider` no `adapter.yaml`
  - Opções: `issuerUri`, `jwkSetUri`
  - Cache de decoder e expiração
- Custom Token Decoder via Groovy (`CustomClosureDecoder`)
  - Exemplo de script Groovy para decodificação customizada
- OAuth2 Client Credentials (interceptor OkHttp)
  - Como o n-gate injeta tokens automaticamente nos backends
  - `useRefreshToken`, `renewBeforeSecs`, `authScopes`
- Políticas de segurança por urlContext (granularidade fina)
- Mascaramento de tokens sensíveis nos logs
- SSL/TLS: Trust store e configuração

---

### 6. Casos de Uso

#### [NEW] [use_cases.md](file:///home/lucas/Projects/n-gate/docs/use_cases.md)

Cenários end-to-end com snippets de configuração:

1. **API Gateway simples** — Proxy transparente para um backend único
2. **Multi-backend com roteamento por path** — Groovy seleciona backend por URI
3. **Gateway com autenticação OAuth2** — Listener secured + token injection no upstream
4. **Mock/Stub de API** — Respostas sintéticas via Groovy (dev/test)
5. **Transformação de resposta** — Response Processor modificando payload
6. **Benchmark e performance** — Listener sem auth + backend estático para medir overhead
7. **Composição de APIs** — Chamada assíncrona a múltiplos backends via `utils.httpClient`

Cada caso inclui:
- Diagrama de fluxo
- Trecho do `adapter.yaml`
- Script Groovy (quando aplicável)
- Comando `curl` para validação

---

### 7. Atualização da Observabilidade

#### [MODIFY] [observability.md](file:///home/lucas/Projects/n-gate/docs/observability.md)

- Substituir menções a "Inventory Adapter" por "n-gate"
- Revisar consistência com a nomenclatura atual

---

### 8. Diagramas PlantUML

#### [NEW] [architecture.puml](file:///home/lucas/Projects/n-gate/docs/diagrams/architecture.puml)

Diagrama C4 Container mostrando:
- Clients → n-gate (Javalin/Jetty) → Backends
- Zipkin, Keycloak como componentes auxiliares
- Groovy Rules Engine como componente interno

#### [NEW] [request_flow.puml](file:///home/lucas/Projects/n-gate/docs/diagrams/request_flow.puml)

Diagrama de Sequência do fluxo completo:
- Client → Listener → Token Decoder → Rules Engine → Upstream (OkHttp) → Response Adapter → Client
- Spans de tracing sobrepostos

---

## Verificação

### Revisão Manual
1. Revisar todos os links internos entre documentos (navegação cruzada)
2. Verificar que cada chave do `adapter.yaml` está documentada em `configuration.md`
3. Confirmar que exemplos de Groovy são baseados na API real (`HttpWorkLoad`, bindings)
4. Validar renderização dos diagramas PlantUML via `https://uml.nishisan.dev/proxy`

### Validação de Diagramas
- Testar a URL de renderização de cada `.puml` para garantir que não há erros de sintaxe
