# Dashboard Tunnel Mode - Plano de Evolucao

Evoluir o dashboard embutido para funcionar de forma nativa em `mode: tunnel`, com UX orientada a TCP/L4 em vez de reaproveitar a visao HTTP do modo proxy.

Objetivo: quando o processo estiver em tunnel mode, o dashboard deve mostrar KPI e topologia do runtime real do tunel, esconder traces, e refletir dinamicamente virtual ports e backends registrados no `TunnelRegistry`.

## User Review Required

> [!IMPORTANT]
> **Comportamento da aba de traces em tunnel mode**: recomendacao deste plano = **ocultar a aba** em vez de mostrar um painel desabilitado. Isso simplifica a UX e evita sugerir uma capacidade que o tunel nao oferece.

> [!IMPORTANT]
> **Fonte da topologia em tunnel mode**: recomendacao deste plano = usar **estado vivo do `TunnelRegistry` + `TunnelEngine`**, e nao a configuracao YAML estatica. Isso torna a topologia aderente ao runtime real.

> [!IMPORTANT]
> **Cadencia de atualizacao da topologia**: recomendacao deste plano = comecar com **polling a cada 5s** em tunnel mode. Se a experiencia ainda parecer lenta, evoluir depois para push via WebSocket/event stream.

> [!IMPORTANT]
> **Nomenclatura de metricas**: recomendacao deste plano = manter as metricas canonicas em `ishin.tunnel.*` e tornar o frontend `mode-aware`, em vez de criar aliases artificiais apenas para encaixar na UI atual.

---

## Current State

- O dashboard ja sobe em `mode: tunnel`, porque roda em servidor dedicado independente do `EndpointManager`.
- O `MetricsCollectorService` ja coleta metricas `ishin.*`, entao as metricas do tunnel entram naturalmente no storage historico.
- Os gauges de conexoes ativas ja existem:
  - `ishin.tunnel.connections.active`
  - `ishin.tunnel.connections.active.per_backend`
- Ha cobertura automatizada para esses gauges em `TunnelMetricsTest`.
- O principal gap atual nao e observabilidade de baixo nivel, e sim **contrato de API + representacao da UI**:
  - a topologia ainda nasce da config HTTP/proxy
  - os cards/graficos atuais procuram metricas HTTP
  - a aba de traces aparece mesmo em tunnel mode

---

## Proposed Changes

### Componente 1: Contrato Mode-Aware da API

O backend do dashboard deve deixar explicito, nas respostas, quando a UI esta operando em `proxy` ou `tunnel`.

#### [MODIFY] [DashboardApiRoutes.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/dashboard/api/DashboardApiRoutes.java)

- Tornar `/api/v1/topology` sensivel ao `serverConfig.getMode()`
- Em `mode=tunnel`, retornar topologia baseada no runtime do tunel
- Enriquecer `/api/v1/health` com campos especificos do tunnel:
  - `virtualListeners`
  - `tunnelGroups`
  - `tunnelMembers`
  - `activeConnections`
  - `dashboardCapabilities`
- Adicionar endpoint novo:
  - `GET /api/v1/tunnel/runtime`

Payload sugerido:

```json
{
  "mode": "tunnel",
  "listeners": 2,
  "groups": 2,
  "members": 4,
  "activeConnections": 18,
  "virtualPorts": [
    {
      "virtualPort": 443,
      "listenerOpen": true,
      "activeMembers": 2,
      "standbyMembers": 1,
      "drainingMembers": 0,
      "members": [
        {
          "backendKey": "node-a:8443",
          "nodeId": "node-a",
          "host": "10.0.0.10",
          "realPort": 8443,
          "status": "ACTIVE",
          "weight": 100,
          "activeConnections": 7,
          "keepaliveAgeSeconds": 1.8
        }
      ]
    }
  ]
}
```

#### [NEW] [TunnelRuntimeSnapshot.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/dashboard/api/TunnelRuntimeSnapshot.java)

DTO imutavel para serializacao da visao runtime do tunnel:

- `TunnelRuntimeSnapshot`
- `VirtualPortSnapshot`
- `TunnelMemberSnapshot`

#### [NEW] [TunnelRuntimeSnapshotFactory.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/dashboard/api/TunnelRuntimeSnapshotFactory.java)

Responsavel por montar snapshots read-only a partir de `TunnelService`, `TunnelRegistry` e `TunnelEngine`, evitando que a API exponha estruturas mutaveis do core.

---

### Componente 2: Exposicao Segura do Runtime do Tunnel

O dashboard precisa ler o estado vivo do tunel sem acoplar a UI diretamente nas estruturas internas.

#### [MODIFY] [TunnelService.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/TunnelService.java)

- Expor acesso read-only ao estado do tunnel:
  - `isRunning()`
  - `getTunnelRegistry()`
  - `getTunnelEngine()`
  - ou, preferencialmente, `getRuntimeSnapshot()` delegando para uma factory

#### [MODIFY] [TunnelRegistry.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/TunnelRegistry.java)

- Adicionar metodos de snapshot:
  - `getGroupsSnapshot()`
  - `getTotalActiveMembers()`
  - `getTotalStandbyMembers()`
  - `getTotalDrainingMembers()`
- Garantir que o snapshot seja imutavel e nao exponha referencias internas

#### [MODIFY] [TunnelEngine.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/TunnelEngine.java)

- Expor:
  - `getOpenListenerPorts()`
  - `isListenerOpen(int virtualPort)`
  - `getTotalActiveConnections()`
- Opcional: manter um contador por virtual port para enriquecer a topologia

#### [MODIFY] [VirtualPortGroup.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/VirtualPortGroup.java)

- Adicionar helpers para snapshot e contagem por status
- Facilitar leitura de `memberCount`, `activeMemberCount`, `standbyMemberCount`, `drainingMemberCount`

---

### Componente 3: Metricas Tunnel-First

As metricas base ja existem, mas a UI tunnel mode pede um conjunto fechado de KPI e graficos.

#### [MODIFY] [TunnelMetrics.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/TunnelMetrics.java)

Manter o que ja existe e completar o conjunto para a UI:

- Ja existentes:
  - `ishin.tunnel.connections.total`
  - `ishin.tunnel.connections.active`
  - `ishin.tunnel.connections.active.per_backend`
  - `ishin.tunnel.connect.duration.seconds`
  - `ishin.tunnel.session.duration.seconds`
  - `ishin.tunnel.bytes.sent.total`
  - `ishin.tunnel.bytes.received.total`
  - `ishin.tunnel.connect.errors.total`
  - `ishin.tunnel.pool.removals.total`
  - `ishin.tunnel.standby.promotions.total`
  - `ishin.tunnel.listener.ports.active`

- Adicionar ou completar:
  - `ishin.tunnel.pool.members`
    - tags: `virtual_port`, `status`
  - `ishin.tunnel.keepalive.age.seconds`
    - tags: `virtual_port`, `backend`
  - `ishin.tunnel.routing.duration.seconds`
    - timer novo para tempo interno de roteamento

#### Definicao da nova metrica de roteamento

`ishin.tunnel.routing.duration.seconds` deve medir o custo interno do tunel antes do handshake TCP com o backend:

- lookup do `VirtualPortGroup`
- selecao de membro
- retries internos e bookkeeping do balancer

Ela deve ser separada de `ishin.tunnel.connect.duration.seconds`, que continua medindo apenas o tempo de `connect()`.

#### [MODIFY] [TunnelEngine.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/TunnelEngine.java)

- Instrumentar `routing.duration`
- Registrar gauges por member quando o backend entrar em uso
- Garantir que as metricas de throughput e conexoes ativas acompanhem lifecycle completo da sessao

---

### Componente 4: Topologia Dinamica do Tunel

A topologia em tunnel mode deve mostrar o runtime de L4, nao listeners HTTP, contextos e scripts.

#### [MODIFY] [DashboardApiRoutes.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/dashboard/api/DashboardApiRoutes.java)

Em `mode=tunnel`, construir um grafo com:

- no central:
  - `type: "tunnel"`
- nos de portas virtuais:
  - `type: "virtual-port"`
- nos de members:
  - `type: "tunnel-member"`

Metadados sugeridos por no:

- `virtual-port`
  - `port`
  - `listenerOpen`
  - `activeMembers`
  - `standbyMembers`
  - `drainingMembers`
- `tunnel-member`
  - `nodeId`
  - `host`
  - `realPort`
  - `status`
  - `weight`
  - `activeConnections`
  - `keepaliveAgeSeconds`

Edges sugeridas:

- `virtual-port -> tunnel`
- `tunnel -> tunnel-member`

#### Estrategia de atualizacao

- V1: polling da topologia a cada `5s` em tunnel mode
- V2 opcional: push de diff/runtime via WebSocket quando o registry mudar

---

### Componente 5: Timeline de Eventos Operacionais

Tunnel mode fica muito mais util se a timeline refletir eventos do registry e do engine.

#### [MODIFY] [DashboardStorageService.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/dashboard/storage/DashboardStorageService.java)

Reutilizar `saveEvent()` e padronizar o schema de eventos do tunnel:

- `LISTENER_OPENED`
- `LISTENER_CLOSED`
- `MEMBER_ADDED`
- `MEMBER_REMOVED`
- `KEEPALIVE_TIMEOUT`
- `STANDBY_PROMOTED`
- `CONNECT_ERROR`

#### [NEW] [TunnelDashboardEventBridge.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/dashboard/TunnelDashboardEventBridge.java)

Bridge opcional entre runtime do tunnel e storage do dashboard.

Recomendacao arquitetural:

- o core do tunnel nao deve depender diretamente do storage do dashboard
- o bridge recebe callbacks/eventos do `TunnelService`
- quando o dashboard estiver habilitado, persiste no `DashboardStorageService`

#### [MODIFY] [TunnelService.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/TunnelService.java)

- Conectar callbacks do `TunnelRegistry` e `TunnelEngine` ao bridge de eventos

#### [MODIFY] [TunnelRegistry.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/TunnelRegistry.java)

- Expor callbacks para:
  - member add
  - member remove
  - promotion
  - keepalive timeout

#### [MODIFY] [TunnelEngine.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/TunnelEngine.java)

- Expor callbacks para:
  - listener open/close
  - connect error

---

### Componente 6: Frontend Mode-Aware

A UI atual deve continuar boa em `proxy`, mas trocar automaticamente de layout em `tunnel`.

#### [MODIFY] [App.tsx](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/App.tsx)

- Ler `health.mode`
- Se `mode === "tunnel"`:
  - esconder a aba `Traces`
  - trocar os cards principais para KPI TCP
  - trocar a aba de topologia para uma visualizacao de tunel
  - trocar a aba de latencia por paines de `connect`, `routing`, `session`, `throughput`

#### [MODIFY] [types.ts](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/types.ts)

- Expandir os tipos de topologia para suportar:
  - `tunnel`
  - `virtual-port`
  - `tunnel-member`
- Adicionar tipos para `TunnelRuntimeSnapshot`

#### [MODIFY] [api.ts](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/api.ts)

- Adicionar `getTunnelRuntime()`

#### [MODIFY] [hooks/useDashboard.ts](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/hooks/useDashboard.ts)

- Adicionar hook `useTunnelRuntime()`
- Em tunnel mode, reduzir intervalo de refresh da topologia para `5s`

---

### Componente 7: Novos Paines e Graficos para Tunnel Mode

Recomendacao: criar componentes novos em vez de distorcer demais os componentes HTTP existentes.

#### [NEW] [TunnelMetricsCards.tsx](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/components/TunnelMetricsCards/TunnelMetricsCards.tsx)

Cards recomendados:

- `Con/s`
  - derivado de `ishin.tunnel.connections.total`
- `Conexoes Ativas`
  - `ishin.tunnel.connections.active`
- `TCP Connect`
  - media ou p95 de `ishin.tunnel.connect.duration.seconds`
- `Routing Interno`
  - `ishin.tunnel.routing.duration.seconds`
- `Throughput`
  - `bytes.sent + bytes.received` por segundo
- `Connect Errors`
  - taxa de `ishin.tunnel.connect.errors.total`

#### [NEW] [TunnelChartsPanel.tsx](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/components/TunnelChartsPanel/TunnelChartsPanel.tsx)

Graficos recomendados:

- conexoes por segundo
- conexoes ativas
- connect duration
- routing duration
- session duration
- throughput
- connect errors

#### [NEW] [TunnelTopologyView.tsx](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/components/TunnelTopologyView/TunnelTopologyView.tsx)

Layout recomendado:

- coluna esquerda: virtual ports
- coluna central: tunnel node
- coluna direita: backend members

Cada member deve mostrar:

- status (`ACTIVE`, `STANDBY`, `DRAINING`)
- peso
- conexoes ativas
- host:porta
- idade do ultimo keepalive

#### [NEW] [TunnelMembersPanel.tsx](file:///home/lucas/Projects/ishin-gateway/ishin-gateway-ui/src/components/TunnelMembersPanel/TunnelMembersPanel.tsx)

Tabela ou painel lateral com ordenacao por:

- virtual port
- status
- active connections
- weight
- keepalive age

---

### Componente 8: Compatibilidade e Reuso

Manter uma unica SPA, com dois modos visuais:

- `proxy-dashboard`
- `tunnel-dashboard`

Nao e recomendado criar um segundo frontend separado. O shell, hooks e infraestrutura de API/WebSocket podem ser compartilhados.

Regras de compatibilidade:

- `mode=proxy`: comportamento atual preservado
- `mode=tunnel`: traces ocultos, cards e topologia especificos
- `dashboard.enabled=false`: nada muda no startup atual

---

### Componente 9: Documentacao

#### [MODIFY] [docs/observability.md](file:///home/lucas/Projects/ishin-gateway/docs/observability.md)

- Documentar como o dashboard se comporta em `proxy` e `tunnel`
- Listar os KPI de tunnel mode
- Documentar que traces nao aparecem em tunnel mode

#### [MODIFY] [docs/architecture.md](file:///home/lucas/Projects/ishin-gateway/docs/architecture.md)

- Adicionar nota sobre dashboard mode-aware e topologia dinamica do tunel

#### [MODIFY] [docs/configuration.md](file:///home/lucas/Projects/ishin-gateway/docs/configuration.md)

- Se necessario, documentar flags futuras de comportamento visual do dashboard

---

## Recommended Delivery Phases

### Fase 1: Backend Runtime + Contrato

- DTOs de snapshot do tunnel
- `/api/v1/tunnel/runtime`
- `/api/v1/health` enriquecido para tunnel mode
- `/api/v1/topology` mode-aware no backend

Entrega esperada:

- a API ja reflete runtime real do tunel
- a UI ainda pode continuar simples

### Fase 2: UI Tunnel-First

- esconder traces em tunnel mode
- novos cards TCP
- novos graficos TCP
- shell `mode-aware`

Entrega esperada:

- o dashboard ja fica util operacionalmente em tunnel mode

### Fase 3: Topologia Dinamica e Timeline

- `TunnelTopologyView`
- painel de members
- eventos operacionais
- refresh mais frequente ou push

Entrega esperada:

- visualizacao viva do registry e do lifecycle dos membros

---

## Verification Plan

### Testes Backend

```bash
mvn test -Dtest=TunnelMetricsTest
mvn test -Dtest=TunnelLoadBalancerTest
mvn test -Dtest=TopologyContextScriptTest
```

Novos testes recomendados:

- `TunnelRuntimeSnapshotFactoryTest`
  - snapshot representa corretamente grupos, ports e members
- `DashboardApiRoutesTunnelModeTest`
  - `/api/v1/topology` retorna grafo de tunnel em `mode=tunnel`
  - `/api/v1/health` expande campos de tunnel
  - `/api/v1/tunnel/runtime` retorna runtime valido
- `TunnelEventBridgeTest`
  - eventos do runtime sao persistidos corretamente

### Testes Frontend

```bash
cd ishin-gateway-ui
npm run build
```

Casos minimos:

- `mode=proxy` continua exibindo traces/tab atual
- `mode=tunnel` nao renderiza aba de traces
- cards tunnel leem `ishin.tunnel.*`
- topologia tunnel renderiza virtual ports e members

### Validacao Manual

Cenario recomendado:

1. subir 1 no tunnel em `mode=tunnel`
2. subir 2 proxies com `tunnel.registration.enabled=true`
3. observar criacao dinamica de virtual ports
4. gerar trafego TCP continuo
5. verificar:
   - `Con/s`
   - `Conexoes Ativas`
   - throughput
   - connect duration
   - topologia refletindo join/leave
6. desligar um proxy e confirmar:
   - remocao visual do member
   - evento no timeline
   - aumento temporario de erro se houver connect failure

---

## Summary

Este plano transforma o dashboard de tunnel mode em uma visao operacional real de L4/TCP, sem quebrar o modo proxy existente.

O caminho recomendado e:

1. tornar backend e frontend `mode-aware`
2. expor runtime real do tunnel via API
3. trocar KPI HTTP por KPI TCP
4. evoluir a topologia para refletir `virtualPort -> tunnel -> members`
5. registrar eventos do lifecycle do registry e do engine

Com isso, o dashboard deixa de ser apenas "acessivel" em tunnel mode e passa a ser realmente util para operar o tunel.
