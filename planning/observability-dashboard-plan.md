# Plano de Implementação — UI de Observabilidade do n-gate

Interface de observabilidade embutida no n-gate com topologia interativa, métricas Prometheus, traces Zipkin e histórico em H2 embedded. Visual "Nordic Tech" — Proposta A.

## User Review Required

> [!IMPORTANT]
> **Porta do Dashboard**: proposta `9200` (configurável). Verificar se não conflita com outros serviços no ambiente.

> [!IMPORTANT]
> **Storage H2 embedded**: zero infraestrutura extra, mas limita o volume de histórico ao disco local. Retenção default de **7 dias** com rollup de 1 minuto (~10k rows/dia estimado). Aceitável?

> [!IMPORTANT]
> **Scope da v1**: este plano cobre a fundação completa (backend + frontend + integrações). É um feature grande (~30+ arquivos novos). Sugiro implementar em branches separadas por fase para revisão incremental.

---

## Proposed Changes

### Componente 1: Configuração e Segurança

Bloco `dashboard:` no `adapter.yaml` controla tudo. IP filter garante acesso restrito.

#### [NEW] [DashboardConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/DashboardConfiguration.java)

POJO para o bloco `dashboard:` do adapter.yaml:
```yaml
dashboard:
  enabled: true
  port: 9200
  bindAddress: "0.0.0.0"
  allowedIps:
    - "127.0.0.1"
    - "::1"
    - "10.0.0.0/24"
  storage:
    type: "h2"
    path: "./data/dashboard"
    retentionHours: 168      # 7 dias
    scrapeIntervalSeconds: 60 # frequência de coleta
  zipkin:
    enabled: true
    baseUrl: "${ZIPKIN_URL:http://localhost:9411}"
```

Campos: `enabled`, `port`, `bindAddress`, `allowedIps` (List\<String\>), `storage` (sub-objeto), `zipkin` (sub-objeto).

#### [MODIFY] [ServerConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/ServerConfiguration.java)

Adicionar campo `private DashboardConfiguration dashboard;` com getter/setter, seguindo exatamente o padrão existente de `admin`, `cluster`, `tunnel`.

#### [NEW] [DashboardIpFilter.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/dashboard/DashboardIpFilter.java)

Filtro de IP que suporta:
- IPs exatos (IPv4 e IPv6)
- Ranges CIDR (`10.0.0.0/24`)
- Usa `java.net.InetAddress` para parsing seguro
- Integrado como `before` handler no Javalin do dashboard
- Retorna **403 Forbidden** se IP não autorizado

---

### Componente 2: Storage (H2 Embedded)

#### [MODIFY] [pom.xml](file:///home/lucas/Projects/n-gate/pom.xml)

Adicionar dependências:
```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

> Não usaremos Spring Data JPA para manter leveza. Acesso via JDBC puro com `HikariCP` (já incluso no Spring Boot).

#### [NEW] [DashboardStorageService.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/dashboard/storage/DashboardStorageService.java)

- Inicializa H2 em file-mode (`jdbc:h2:file:./data/dashboard/metrics`)
- Schema auto-criado no startup via DDL SQL embeddado
- Tabelas:
  - `metric_snapshot(id, timestamp, metric_name, tags_json, value)` — snapshots periódicos
  - `event_log(id, timestamp, event_type, source, details_json)` — eventos de CB, rate limit, pool
- Métodos: `saveSnapshot()`, `queryMetrics(name, from, to)`, `saveEvent()`, `purgeExpired()`
- Cleanup job com `@Scheduled` baseado em `retentionHours`

#### [NEW] [MetricsCollectorService.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/dashboard/collector/MetricsCollectorService.java)

- Scheduled task que lê métricas do `MeterRegistry` (in-process, sem HTTP)
- Converte meters relevantes (`ngate.*`, `resilience4j.*`, `jvm.*`) em snapshots
- Persiste via `DashboardStorageService` a cada `scrapeIntervalSeconds`
- Rollup: armazena valor instantâneo de cada métrica com timestamp

---

### Componente 3: API REST + WebSocket (Javalin)

#### [NEW] [DashboardServer.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/dashboard/DashboardServer.java)

Javalin standalone na porta `dashboard.port` (default 9200), seguindo o padrão do `EndpointWrapper`:
- `before("/*")` → `DashboardIpFilter`
- Serve SPA React de `classpath:/static/dashboard/`
- Fallback: qualquer rota não-API retorna `index.html` (SPA routing)
- Lifecycle: start no `@PostConstruct`, stop no shutdown hook

#### [NEW] [DashboardApiRoutes.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/dashboard/api/DashboardApiRoutes.java)

Endpoints REST no prefixo `/api/v1/`:

| Endpoint | Método | Descrição |
|----------|--------|-----------|
| `/api/v1/topology` | GET | Retorna grafo: listeners, backends, cluster members, circuit breakers |
| `/api/v1/metrics/current` | GET | Métricas atuais do `MeterRegistry` em JSON |
| `/api/v1/metrics/history` | GET | Métricas históricas do H2 (`?name=...&from=...&to=...`) |
| `/api/v1/traces` | GET | Proxy HTTP para Zipkin API (`/api/v2/traces`) |
| `/api/v1/traces/{traceId}` | GET | Proxy para Zipkin trace específico |
| `/api/v1/health` | GET | Status dos backends, circuit breaker states, rate limiter permits |
| `/api/v1/events` | GET | Últimos N eventos do `event_log` |

#### [NEW] WebSocket: `/ws/metrics`

- Push de métricas a cada 5s para clientes conectados
- Formato: JSON com subset das métricas atuais (RPS, latência, erros, CB states)
- Usa Javalin WebSocket handler nativo

---

### Componente 4: React SPA

#### [NEW] Diretório `n-gate-ui/`

Scaffold via `npx create-vite`:
```
n-gate-ui/
  src/
    components/
      TopologyView/       # Grafo interativo (React Flow)
      MetricsCards/        # Cards de KPIs
      EventTimeline/       # Timeline de eventos (sidebar)
      TracesPanel/         # Painel de traces
      CircuitBreakerShield/ # Indicador hexagonal de CB
    hooks/
      useMetrics.ts       # WebSocket + polling de métricas
      useTopology.ts      # Fetch da topologia
      useTraces.ts        # Fetch dos traces Zipkin
    styles/
      tokens.css          # Design tokens Nordic Tech
      global.css          # Reset + base styles
    App.tsx               # Shell principal
    main.tsx              # Entry point
  vite.config.ts
  package.json
  tsconfig.json
```

**Dependências React**:
- `@xyflow/react` (React Flow) — para o grafo topológico
- `recharts` — gráficos de métricas/sparklines
- `lucide-react` — ícones

**Design Tokens (Nordic Tech)**:
```css
:root {
  --bg-primary: #1A1B1E;
  --bg-surface: #25262B;
  --bg-elevated: #2C2E33;
  --accent-primary: #748FFC;
  --accent-secondary: #A5D8FF;
  --text-primary: #C1C2C5;
  --text-secondary: #909296;
  --success: #51CF66;
  --warning: #FCC419;
  --error: #FF6B6B;
  --font-mono: 'JetBrains Mono', monospace;
  --font-sans: 'Inter', sans-serif;
}
```

#### Build Integration

#### [MODIFY] [pom.xml](file:///home/lucas/Projects/n-gate/pom.xml)

Adicionar `frontend-maven-plugin` para build do React durante `mvn package`:
```xml
<plugin>
    <groupId>com.github.eirslett</groupId>
    <artifactId>frontend-maven-plugin</artifactId>
    <version>1.15.1</version>
    <configuration>
        <workingDirectory>${project.basedir}/n-gate-ui</workingDirectory>
        <installDirectory>target</installDirectory>
    </configuration>
    <executions>
        <!-- install node + npm -->
        <!-- npm install -->
        <!-- npm run build -->
        <!-- copy dist → src/main/resources/static/dashboard/ -->
    </executions>
</plugin>
```

O build do React é integrado ao Maven — `mvn package` produz o JAR com o SPA embutido.

---

### Componente 5: Documentação

#### [MODIFY] [observability.md](file:///home/lucas/Projects/n-gate/docs/observability.md)
Nova seção sobre o Dashboard UI com configuração, endpoints API e screenshots.

#### [MODIFY] [configuration.md](file:///home/lucas/Projects/n-gate/docs/configuration.md)
Documentação do bloco `dashboard:` no adapter.yaml.

#### [NEW] [dashboard_architecture.puml](file:///home/lucas/Projects/n-gate/docs/diagrams/dashboard_architecture.puml)
Diagrama C4 Component do dashboard.

---

## Verification Plan

### Testes Automatizados

**1. Teste do IP Filter**
```bash
# Criar: src/test/java/dev/nishisan/ngate/dashboard/DashboardIpFilterTest.java
# Testar: IPs permitidos, IPs bloqueados, ranges CIDR, IPv6
mvn test -pl . -Dtest=DashboardIpFilterTest
```

**2. Teste do Storage Service**
```bash
# Criar: src/test/java/dev/nishisan/ngate/dashboard/DashboardStorageServiceTest.java
# Testar: insert de snapshots, query por range, purge de expirados
# H2 in-memory para testes
mvn test -pl . -Dtest=DashboardStorageServiceTest
```

**3. Teste de integração do Dashboard Server (Testcontainers)**
```bash
# Criar: src/test/java/dev/nishisan/ngate/dashboard/DashboardIntegrationTest.java
# Testar: endpoint /api/v1/topology retorna 200, IP filter retorna 403
mvn test -pl . -Dtest=DashboardIntegrationTest
```

### Validação no Browser

**4. Smoke test do SPA**
- Subir n-gate localmente com `dashboard.enabled: true`
- Acessar `http://localhost:9200` no browser
- Verificar: SPA carrega, grafo topológico renderiza nós dos listeners e backends
- Verificar: cards de métricas mostram dados em tempo real
- Verificar: acesso de IP não autorizado retorna 403

### Validação Manual (solicitar ao usuário)

**5. Validação visual**
- Usuário verifica se a paleta Nordic Tech está correta
- Usuário verifica se a topologia reflete a configuração real do `adapter.yaml`
- Usuário testa acesso de diferentes IPs/ranges
