# n-gate Tunnel Mode — Especificação Técnica

## 1. Visão Geral

O **Tunnel Mode** é um modo de operação alternativo do n-gate que atua como um **load balancer TCP (L4)** na frente de múltiplas instâncias do n-gate em modo proxy. O objetivo é oferecer uma solução **auto-contida** de alta disponibilidade e balanceamento de carga, eliminando a necessidade de componentes externos como Nginx, HAProxy ou cloud ingress controllers.

### Princípios de Design

| Princípio | Descrição |
|-----------|-----------|
| **Push Registration** | Backends registram-se ativamente no túnel via NGrid mesh. O túnel nunca faz polling. |
| **Túnel burro** | Toda a inteligência está nos nós proxy. O túnel apenas lê o registry e faz pipe TCP. |
| **Listeners dinâmicos** | O túnel não abre portas pré-configuradas. Portas são abertas sob demanda conforme backends se registram. |
| **Virtual Port Groups** | Backends declaram a qual porta virtual pertencem, independente da porta real em que escutam. |
| **Graceful lifecycle** | Backends notificam entrada e saída do pool. O túnel reage, nunca inicia. |

### Modos de operação do n-gate

```
┌─────────────────────────────────────────────────┐
│                   n-gate                         │
│                                                  │
│  ┌──────────────┐       ┌─────────────────────┐  │
│  │  mode: proxy  │       │  mode: tunnel       │  │
│  │              │       │                     │  │
│  │ HTTP L7      │       │ TCP L4              │  │
│  │ Groovy Rules │       │ Pass-through puro   │  │
│  │ OAuth        │       │ Zero config         │  │
│  │ Observab.    │       │ Registry-driven     │  │
│  │ Circuit Brk  │       │ Virtual Port Groups │  │
│  └──────────────┘       └─────────────────────┘  │
│                                                  │
│  Um processo é SEMPRE um OU outro, nunca ambos.  │
└─────────────────────────────────────────────────┘
```

---

## 2. Topologia

### 2.1. Cenário Básico — Múltiplas VMs

```
                              ┌─────────────────────┐
                              │   VM-2               │
                              │   n-gate (proxy)     │
                              │   listen: 8080       │
                         ┌───▶│   virtualPort: 8080  │
                         │    └─────────────────────┘
┌──────────────────┐     │
│   VM-1            │     │    ┌─────────────────────┐
│   n-gate (tunnel) │     │    │   VM-3               │
│                   │─────┼───▶│   n-gate (proxy)     │
│   :8080 (virtual) │     │    │   listen: 8080       │
│                   │     │    │   virtualPort: 8080  │
└──────────────────┘     │    └─────────────────────┘
       ▲                  │
       │                  │    ┌─────────────────────┐
    Clients               │    │   VM-4               │
                          └───▶│   n-gate (proxy)     │
                               │   listen: 8080       │
                               │   virtualPort: 8080  │
                               └─────────────────────┘
```

### 2.2. Cenário Co-located — Mesma VM (Virtual Port Group)

```
┌──────────────────────────────────────────────┐
│                    VM-1                       │
│                                              │
│  ┌──────────────────┐                        │
│  │ n-gate (tunnel)  │                        │
│  │ :8080 (virtual)  │───┐                    │
│  └──────────────────┘   │                    │
│                          │  ┌──────────────┐  │
│                          ├─▶│ proxy-1      │  │
│                          │  │ :8081 (real) │  │
│                          │  │ vPort: 8080  │  │
│                          │  └──────────────┘  │
│                          │                    │
│                          │  ┌──────────────┐  │
│                          └─▶│ proxy-2      │  │
│                             │ :8082 (real) │  │
│                             │ vPort: 8080  │  │
│                             └──────────────┘  │
│                                              │
└──────────────────────────────────────────────┘
```

### 2.3. Cenário Misto — Multi-porta + Multi-VM

```
                              ┌───────────────────────┐
                              │  VM-2                  │
                              │  proxy-1               │
                         ┌───▶│  :8081 → vPort 8080   │
                         │    │  :8444 → vPort 8443   │
┌──────────────────┐     │    └───────────────────────┘
│  VM-1             │     │
│  n-gate (tunnel)  │     │    ┌───────────────────────┐
│                   │─────┤    │  VM-2                  │
│  :8080 (virtual)  │     │    │  proxy-2               │
│  :8443 (virtual)  │     ├───▶│  :8082 → vPort 8080   │
│                   │     │    │  :8445 → vPort 8443   │
└──────────────────┘     │    └───────────────────────┘
                         │
                         │    ┌───────────────────────┐
                         │    │  VM-3                  │
                         └───▶│  proxy-3               │
                              │  :8080 → vPort 8080   │
                              │  :8443 → vPort 8443   │
                              └───────────────────────┘
```

---

## 3. Protocolo de Registro (Push Model via NGrid)

### 3.1. Estrutura do Registry Entry

Cada nó proxy publica um registro no NGrid `NMap` sob a chave `tunnel:registry:{nodeId}`.

```yaml
# Registry entry publicado pelo proxy no NGrid NMap
nodeId: "proxy-1"
host: "10.0.0.5"
listeners:
  - virtualPort: 8080
    realPort: 8081
    protocol: "tcp"
  - virtualPort: 8443
    realPort: 8444
    protocol: "tcp"
status: "ACTIVE"          # ACTIVE | DRAINING | STANDBY
weight: 100               # peso para weighted LB (default: 100)
lastKeepAlive: 1742122000 # epoch millis — atualizado pelo proxy a cada heartbeat
registeredAt: 1742120000  # epoch millis — timestamp do primeiro registro
```

### 3.2. Ciclo de Vida do Registro

```
┌─────────┐    register     ┌─────────────┐
│  BOOT   │───────────────▶│   ACTIVE     │◀──── keepalive (a cada N seg)
└─────────┘                 └──────┬──────┘
                                   │
                          drain    │   crash (keepalive timeout)
                        (graceful) │   (detecção passiva)
                                   │
                            ┌──────▼──────┐
                            │  DRAINING   │
                            └──────┬──────┘
                                   │
                          deregister│  ou timeout
                                   │
                            ┌──────▼──────┐
                            │  REMOVED    │
                            └─────────────┘
```

### 3.3. Transições de Estado

| Evento | Ação do Proxy | Ação do Túnel |
|--------|---------------|---------------|
| **Startup** | Publica registro com `status: ACTIVE` e `lastKeepAlive: now()` | Adiciona ao pool do virtualPort. Abre listener se for o primeiro membro do grupo. |
| **Keepalive** | Atualiza `lastKeepAlive: now()` no NMap | Confirma que o membro está vivo. |
| **Graceful shutdown** | Atualiza `status: DRAINING` → aguarda conexões drenarem → remove registro | Para de enviar novas conexões. Remove do pool após deregistro. Fecha listener se for o último membro. |
| **Crash** | Nada (processo morreu) | `lastKeepAlive` expira → remove do pool automaticamente. |
| **Standby** | Publica com `status: STANDBY` | Não roteia tráfego para este membro. Promove para ACTIVE se todos os ACTIVE morrerem. |

### 3.4. Detecção de Falha — Modelo em Duas Camadas

A detecção de falha opera em **duas camadas complementares**, priorizando velocidade de detecção:

#### Camada 1: Detecção imediata por IOException (primária)

Quando o túnel tenta abrir uma conexão TCP com um backend e recebe uma exceção de I/O, a falha é detectada **instantaneamente** (milissegundos). Este é o mecanismo **mais rápido e confiável** de detecção.

| Exceção Java | Significado | Ação do Túnel |
|--------------|-------------|---------------|
| `ConnectException` (Connection Refused) | Processo morreu, porta fechou | **Remoção imediata** do pool. Tenta próximo membro. |
| `NoRouteToHostException` | Rede caiu, host inalcançável | **Remoção imediata** do pool. |
| `SocketTimeoutException` | Processo sobrecarregado ou lento | **Não remove**. Faz retry no próximo membro para esta conexão. Incrementa contador de falhas. Se atingir threshold (3 falhas consecutivas), remove do pool. |
| `SocketException` (Connection Reset) | Processo crashou durante handshake | **Remoção imediata** do pool. |

Após uma remoção por IOException, o membro **pode ser reinserido** no pool se um novo keepalive for recebido via NGrid (indicando que o processo voltou).

```
  Client              Tunnel                    Proxy-1 (DEAD)
    │                    │                          │
    │── TCP connect ───▶│                          │
    │                    │── connect(10.0.0.5:8081)─▶ ╳ ConnectException
    │                    │                          │
    │                    │── remoção imediata        │
    │                    │   de proxy-1 do pool      │
    │                    │                          │
    │                    │── retry: connect          │
    │                    │   proxy-2 (10.0.0.5:8082)│
    │                    │── ✅ pipe established ────│──▶ Proxy-2
    │◀── TCP data ───────│                          │
    │                    │                          │
```

> [!IMPORTANT]
> O `IOException` no `connect()` dá detecção em **milissegundos**, enquanto o keepalive timeout leva **segundos**. Por isso, em cenários com tráfego ativo, a falha é detectada quase instantaneamente — o primeiro cliente que tentar conectar descobre o problema.

#### Camada 2: Keepalive timeout via lastKeepAlive (rede de segurança)

O keepalive existe para cobrir o cenário onde **não há tráfego** sendo roteado para um backend que morreu. Sem tráfego, nenhum `IOException` seria gerado, e o backend morto ficaria no pool indefinidamente.

| Parâmetro | Definido em | Valor Default | Descrição |
|-----------|------------|---------------|-----------|
| `keepaliveInterval` | **Proxy** | 3 segundos | Frequência com que o proxy atualiza `lastKeepAlive` no NMap. Publicado no registry. |
| `missedKeepalives` | **Túnel** | 3 | Quantidade de keepalives perdidos consecutivos que o túnel tolera antes de remover o membro. |
| `drainTimeout` | **Túnel** | 30 segundos | Tempo máximo de espera para conexões ativas drenarem durante shutdown. |

**Cálculo do timeout efetivo**: `keepaliveInterval × missedKeepalives`.

Exemplo: se o proxy declara `keepaliveInterval: 3s` e o túnel configura `missedKeepalives: 3`, o membro será removido após **9 segundos** sem heartbeat.

O túnel verifica periodicamente o `lastKeepAlive` de cada membro. Se `now() - lastKeepAlive > keepaliveInterval × missedKeepalives`, o membro é removido do pool.

> [!TIP]
> Como o `keepaliveInterval` é declarado pelo proxy no registry, o túnel se adapta automaticamente. Se um proxy muda seu intervalo de 3s para 5s, o túnel recalcula o threshold sem necessidade de reconfiguração.

#### Resumo: quando cada camada atua

| Cenário | Camada que detecta | Latência |
|---------|-------------------|----------|
| Backend crashou + há tráfego ativo | **IOException** (Camada 1) | Milissegundos |
| Backend crashou + sem tráfego | **Keepalive timeout** (Camada 2) | `keepaliveInterval × missedKeepalives` (default: ~9s) |
| Backend sobrecarregado (lento) | **IOException** (SocketTimeout) + threshold | Segundos (após N falhas) |
| Backend fez graceful shutdown | **Registry** (status: DRAINING) | Imediato (notificação explícita) |
| Rede particionada | Ambas — IOException para novas conexões + keepalive para confirmação | Milissegundos a segundos |

---

## 4. Virtual Port Groups

### 4.1. Conceito

Um **Virtual Port Group** é um agrupamento lógico de backends que servem a mesma porta virtual, independente de suas portas físicas reais.

```
Virtual Port Group 8080
├── proxy-1  →  10.0.0.5:8081  (ACTIVE,  weight: 100)
├── proxy-2  →  10.0.0.5:8082  (ACTIVE,  weight: 100)
└── proxy-3  →  10.0.0.6:8080  (ACTIVE,  weight: 100)

Virtual Port Group 8443
├── proxy-1  →  10.0.0.5:8444  (ACTIVE,  weight: 100)
└── proxy-3  →  10.0.0.6:8443  (STANDBY, weight: 50)
```

### 4.2. Regras

1. O `virtualPort` é declarado pelo backend no registro. O túnel nunca define portas.
2. O túnel abre um `ServerSocketChannel` na porta virtual quando o **primeiro** membro do grupo se registra.
3. O túnel fecha o `ServerSocketChannel` quando o **último** membro do grupo é removido.
4. Cada grupo tem seu pool de LB independente.
5. Um backend pode participar de múltiplos grupos (uma entrada por listener).

### 4.3. Algoritmos de Load Balancing

| Algoritmo | Descrição | Quando usar |
|-----------|-----------|-------------|
| `round-robin` | Distribui sequencialmente entre membros ativos | Default. Simples e previsível. |
| `least-connections` | Roteia para o membro com menos conexões TCP ativas | Quando backends têm capacidades diferentes ou requests com duração variável. |
| `weighted-round-robin` | Round-robin com peso proporcional ao `weight` do registro | Quando membros têm capacidades diferentes (e.g., VMs com CPU diferente). |

> [!NOTE]
> O algoritmo de LB pode ser configurado globalmente no `adapter.yaml` do túnel, ou sobrescrito por grupo via registro do backend.

---

## 5. Exemplos de Configuração YAML

### 5.1. adapter.yaml — Modo Tunnel

```yaml
# adapter.yaml do nó TÚNEL
# Configuração mínima — toda a lógica vem do registry

mode: tunnel

tunnel:
  # Algoritmo de LB global (default: round-robin)
  loadBalancing: least-connections

  # Detecção de falha por keepalive
  missedKeepalives: 3        # keepalives perdidos tolerados (timeout = interval × este valor)
  drainTimeout: 30           # segundos máximo para drenar conexões no shutdown

  # Bind address para os listeners virtuais (default: 0.0.0.0)
  bindAddress: "0.0.0.0"

  # Promoção automática de STANDBY quando todos ACTIVE morrerem
  autoPromoteStandby: true

# NGrid mesh — obrigatório para registro dos backends
cluster:
  enabled: true
  meshPort: 5701
  multicastEnabled: true
  multicastGroup: "239.0.0.1"
  multicastPort: 5702
```

### 5.2. adapter.yaml — Modo Proxy (cenário multi-VM)

```yaml
# adapter.yaml do nó PROXY (cada VM)
# Configuração normal + registro no túnel

mode: proxy

# Registro no túnel via NGrid
tunnel:
  registration:
    enabled: true
    keepaliveInterval: 3    # segundos entre heartbeats
    status: ACTIVE          # ACTIVE | STANDBY
    weight: 100
    # virtualPort: inferido automaticamente dos listeners abaixo

# Endpoints normais do proxy
endpoints:
  default:
    rulesBasePath: "rules"
    ruleMapping: "default/Rules.groovy"
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 8080       # porta real
        virtualPort: 8080      # porta virtual no túnel
        defaultBackend: "api"
      https:
        listenAddress: "0.0.0.0"
        listenPort: 8443
        virtualPort: 8443
        ssl: true
        sslConfiguration:
          certFile: "/etc/ssl/cert.pem"
          keyFile: "/etc/ssl/key.pem"
        defaultBackend: "api"
    backends:
      api:
        backendName: "api"
        members:
          - url: "http://backend-service:3000"

# NGrid mesh
cluster:
  enabled: true
  meshPort: 5701
  multicastEnabled: true
  multicastGroup: "239.0.0.1"
  multicastPort: 5702
```

### 5.3. adapter.yaml — Modo Proxy (cenário co-located, 2 processos na mesma VM)

```yaml
# adapter.yaml do proxy-1 (mesma VM que proxy-2)

mode: proxy

tunnel:
  registration:
    enabled: true
    keepaliveInterval: 3
    status: ACTIVE
    weight: 100

endpoints:
  default:
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 8081       # porta REAL (diferente do proxy-2)
        virtualPort: 8080      # mesma porta VIRTUAL → mesmo grupo
        defaultBackend: "api"
    backends:
      api:
        backendName: "api"
        members:
          - url: "http://backend-service:3000"

cluster:
  enabled: true
  meshPort: 5701
```

```yaml
# adapter.yaml do proxy-2 (mesma VM que proxy-1)

mode: proxy

tunnel:
  registration:
    enabled: true
    keepaliveInterval: 3
    status: ACTIVE
    weight: 100

endpoints:
  default:
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 8082       # porta REAL diferente
        virtualPort: 8080      # mesmo grupo virtual
        defaultBackend: "api"
    backends:
      api:
        backendName: "api"
        members:
          - url: "http://backend-service:3000"

cluster:
  enabled: true
  meshPort: 5703             # porta mesh diferente (mesma VM)
```

### 5.4. adapter.yaml — Modo Proxy (standby)

```yaml
# adapter.yaml de um proxy STANDBY

mode: proxy

tunnel:
  registration:
    enabled: true
    keepaliveInterval: 3
    status: STANDBY            # não recebe tráfego até promoção
    weight: 50

endpoints:
  default:
    listeners:
      http:
        listenAddress: "0.0.0.0"
        listenPort: 8082
        virtualPort: 8080
        defaultBackend: "api"
    backends:
      api:
        backendName: "api"
        members:
          - url: "http://backend-service:3000"
```

---

## 6. Diagramas de Sequência

### 6.1. Registro e Abertura de Listener

```
  Proxy-1              NGrid Mesh            Tunnel
    │                      │                    │
    │── join mesh ────────▶│                    │
    │                      │◀── mesh event ─────│ (tunnel observa o mesh)
    │                      │                    │
    │── put registry ─────▶│                    │
    │   {nodeId: proxy-1,  │                    │
    │    vPort: 8080,      │                    │
    │    realPort: 8081,   │                    │
    │    status: ACTIVE,   │                    │
    │    lastKeepAlive: T} │                    │
    │                      │── notify change ──▶│
    │                      │                    │── grupo 8080: 1 membro
    │                      │                    │── abrir ServerSocket :8080
    │                      │                    │
    │                      │                    │   ✅ Listener 8080 ATIVO
    │                      │                    │
```

### 6.2. Keepalive Periódico

```
  Proxy-1              NGrid Mesh            Tunnel
    │                      │                    │
    │── update registry ──▶│                    │
    │   {lastKeepAlive: T1}│── notify ────────▶│
    │                      │                    │── atualiza timestamp
    │     (3 seg)          │                    │
    │── update registry ──▶│                    │
    │   {lastKeepAlive: T2}│── notify ────────▶│
    │                      │                    │── atualiza timestamp
    │                      │                    │
```

### 6.3. Graceful Shutdown (Rolling Restart)

```
  Proxy-1              NGrid Mesh            Tunnel             Client
    │                      │                    │                   │
    │── update status ────▶│                    │                   │
    │   {status: DRAINING} │── notify ────────▶│                   │
    │                      │                    │── stop routing    │
    │                      │                    │   new connections │
    │                      │                    │   to proxy-1      │
    │                      │                    │                   │
    │   (aguarda drain)    │                    │── conexões ativas │
    │                      │                    │   continuam até   │
    │                      │                    │   encerrar        │
    │                      │                    │                   │
    │── remove registry ──▶│                    │                   │
    │   (deregister)       │── notify ────────▶│                   │
    │                      │                    │── remove do pool  │
    │── shutdown ──────────│                    │                   │
    │                      │                    │                   │
    │     (restart)        │                    │                   │
    │                      │                    │                   │
    │── join mesh ────────▶│                    │                   │
    │── put registry ─────▶│                    │                   │
    │   {status: ACTIVE}   │── notify ────────▶│                   │
    │                      │                    │── add to pool     │
    │                      │                    │                   │
```

### 6.4. Crash Recovery (Keepalive Timeout)

```
  Proxy-1              NGrid Mesh            Tunnel
    │                      │                    │
    │── update registry ──▶│                    │
    │   {lastKeepAlive: T} │── notify ────────▶│
    │                      │                    │
    │   ╳ CRASH            │                    │
    │                      │                    │
    │                      │   (keepalive-timeout expira)
    │                      │                    │── now() - T > 10s
    │                      │                    │── remove proxy-1
    │                      │                    │   do pool
    │                      │                    │
    │                      │                    │   (se último membro)
    │                      │                    │── fechar listener
    │                      │                    │   :8080
    │                      │                    │
```

### 6.5. Promoção de Standby

```
  Proxy-1 (ACTIVE)     Proxy-2 (STANDBY)    Tunnel
    │                      │                    │
    │   Pool 8080:         │                    │
    │   [proxy-1: ACTIVE]  │                    │
    │   [proxy-2: STANDBY] │                    │
    │                      │                    │
    │   ╳ CRASH            │                    │
    │                      │                    │── keepalive timeout
    │                      │                    │── remove proxy-1
    │                      │                    │
    │                      │                    │── zero ACTIVE no grupo
    │                      │                    │── autoPromoteStandby
    │                      │                    │── proxy-2 promovido
    │                      │                    │   a ACTIVE
    │                      │                    │
    │                      │◀── routing start ──│
    │                      │                    │
```

---

## 7. Cenários de Deploy

### 7.1. Standalone (sem túnel)

```yaml
mode: proxy
# Sem bloco tunnel.registration
# Funciona exatamente como hoje
```

Nenhuma mudança no comportamento atual. Retrocompatível.

### 7.2. Cluster simples (3 VMs + 1 Túnel)

| VM | Modo | Real Port | Virtual Port | Status |
|----|------|-----------|-------------|--------|
| VM-1 | tunnel | — | 8080 (dinâmico) | — |
| VM-2 | proxy | 8080 | 8080 | ACTIVE |
| VM-3 | proxy | 8080 | 8080 | ACTIVE |
| VM-4 | proxy | 8080 | 8080 | ACTIVE |

### 7.3. Co-located Active/Active (1 VM)

| VM | Processo | Modo | Real Port | Virtual Port | Status |
|----|----------|------|-----------|-------------|--------|
| VM-1 | tunnel | tunnel | — | 8080 | — |
| VM-1 | proxy-1 | proxy | 8081 | 8080 | ACTIVE |
| VM-1 | proxy-2 | proxy | 8082 | 8080 | ACTIVE |

### 7.4. Co-located Active/Passive (1 VM)

| VM | Processo | Modo | Real Port | Virtual Port | Status |
|----|----------|------|-----------|-------------|--------|
| VM-1 | tunnel | tunnel | — | 8080 | — |
| VM-1 | proxy-1 | proxy | 8081 | 8080 | ACTIVE |
| VM-1 | proxy-2 | proxy | 8082 | 8080 | STANDBY |

### 7.5. Multi-porta com Rolling Restart

| VM | Modo | Real Ports | Virtual Ports | Status |
|----|------|-----------|--------------|--------|
| VM-1 | tunnel | — | 8080, 8443 | — |
| VM-2 | proxy | 8080, 8443 | 8080, 8443 | ACTIVE |
| VM-3 | proxy | 8080, 8443 | 8080, 8443 | ACTIVE |

Rolling restart: VM-2 vai DRAINING → restart → ACTIVE. VM-3 assume sozinha durante o restart.

---

## 8. Rolling Restart via Cluster (Config Reload)

O Tunnel Mode habilita o cenário de **reload de configuração sem downtime** discutido anteriormente:

1. Operador atualiza o `adapter.yaml` nos nós proxy.
2. Envia comando via Admin API: `POST /admin/reload`.
3. O proxy atualiza seu registro para `status: DRAINING`.
4. O túnel para de enviar novas conexões para esse nó.
5. O proxy aguarda conexões ativas drenarem (até `drainTimeout`).
6. O proxy faz `deregister` → graceful shutdown → restart com nova config.
7. O proxy re-registra com `status: ACTIVE`.
8. Repete para o próximo nó (coordenado pelo NGrid leader).

> [!IMPORTANT]
> O reload de configuração é **suportado apenas em modo cluster** (mínimo 2 proxies).
> Em single-node, alterações no `adapter.yaml` exigem restart manual.

---

## 9. Observabilidade TCP (Prometheus)

Embora o túnel não entenda HTTP, ele tem visibilidade completa sobre as **conexões TCP** e o **estado do pool**. Todas as métricas são expostas via Prometheus no endpoint padrão do n-gate (`/metrics`).

### 9.1. Métricas de Conexão

| Métrica | Tipo | Labels | Descrição |
|---------|------|--------|-----------|
| `ngate_tunnel_connections_total` | Counter | `virtual_port`, `backend` | Total de conexões TCP aceitas |
| `ngate_tunnel_connections_active` | Gauge | `virtual_port`, `backend` | Conexões TCP ativas no momento |
| `ngate_tunnel_session_duration_seconds` | Histogram | `virtual_port`, `backend` | Duração da sessão TCP (accept → close) |
| `ngate_tunnel_connect_duration_seconds` | Histogram | `virtual_port`, `backend` | Latência do handshake TCP com o backend |

### 9.2. Métricas de Throughput

| Métrica | Tipo | Labels | Descrição |
|---------|------|--------|-----------|
| `ngate_tunnel_bytes_sent_total` | Counter | `virtual_port`, `backend` | Bytes transferidos client → backend |
| `ngate_tunnel_bytes_received_total` | Counter | `virtual_port`, `backend` | Bytes transferidos backend → client |

### 9.3. Métricas de Falha

| Métrica | Tipo | Labels | Descrição |
|---------|------|--------|-----------|
| `ngate_tunnel_connect_errors_total` | Counter | `virtual_port`, `backend`, `error_type` | Falhas de conexão com backend. `error_type`: `refused`, `timeout`, `reset`, `no_route` |
| `ngate_tunnel_pool_removals_total` | Counter | `virtual_port`, `backend`, `reason` | Remoções do pool. `reason`: `io_exception`, `keepalive_timeout`, `graceful` |

### 9.4. Métricas de Saúde do Pool

| Métrica | Tipo | Labels | Descrição |
|---------|------|--------|-----------|
| `ngate_tunnel_pool_members` | Gauge | `virtual_port`, `status` | Membros por virtualPort e status (`active`, `standby`, `draining`) |
| `ngate_tunnel_keepalive_age_seconds` | Gauge | `virtual_port`, `backend` | Segundos desde o último keepalive de cada membro |
| `ngate_tunnel_listener_ports_active` | Gauge | — | Total de listeners virtuais abertos |
| `ngate_tunnel_standby_promotions_total` | Counter | `virtual_port` | Promoções automáticas de STANDBY → ACTIVE |

### 9.5. Exemplos de Queries PromQL

```promql
# Taxa de conexões por segundo por backend (últimos 5 min)
rate(ngate_tunnel_connections_total[5m])

# Distribuição de carga: conexões ativas por backend
ngate_tunnel_connections_active

# Throughput total in+out (bytes/s)
rate(ngate_tunnel_bytes_sent_total[5m]) + rate(ngate_tunnel_bytes_received_total[5m])

# Taxa de erros de conexão por tipo
rate(ngate_tunnel_connect_errors_total[5m])

# Alerting: membro com keepalive próximo do threshold
ngate_tunnel_keepalive_age_seconds > 6  # alerta quando > 2x keepaliveInterval

# P95 de duração de sessão TCP
histogram_quantile(0.95, rate(ngate_tunnel_session_duration_seconds_bucket[5m]))

# Remoções do pool por motivo (últimas 24h)
increase(ngate_tunnel_pool_removals_total[24h])
```

---

## 10. Limitações Conhecidas

| Limitação | Motivo | Alternativa |
|-----------|--------|-------------|
| Sem host-based routing no túnel | L4 não inspeciona HTTP headers | O proxy downstream faz virtual hosting normalmente |
| TLS terminado no proxy, não no túnel | Pass-through TCP não vê o TLS | Cada proxy precisa ter o certificado (hostname-based, ex: Let's Encrypt) |
| Sem métricas HTTP no túnel | L4 não entende HTTP | Métricas TCP ricas (seção 9). Métricas HTTP completas nos proxies. |
| Mínimo 2 nós para rolling restart | 1 nó = downtime durante restart | Aceitar breve downtime ou usar Active/Passive co-located |
