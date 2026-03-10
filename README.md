# n-gate

> API Gateway & Reverse Proxy de alta performance com motor de regras dinâmicas Groovy, cluster mode (NGrid), circuit breaker, rate limiting, observabilidade integrada (Zipkin + Prometheus) e autenticação OAuth2/JWT — construído em Java 21.

[![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
[![Javalin 7](https://img.shields.io/badge/Javalin-7.0-green)](https://javalin.io/)

---

## O que é o n-gate?

O **n-gate** é um gateway HTTP programável que atua como proxy reverso entre seus clientes e backends. Ele permite:

- **Roteamento dinâmico** — Scripts Groovy decidem em runtime para qual backend encaminhar o request, podendo alterar headers, path, query string e body.
- **Autenticação transparente** — Injeta tokens OAuth2 automaticamente nos requests upstream e valida JWT nos requests de entrada.
- **Observabilidade nativa** — Gera traces distribuídos (Brave/Zipkin) com 11+ spans por request, incluindo propagação B3 bidirecional e header `x-trace-id` para correlação.
- **Streaming de alta performance** — Modo `returnPipe` transfere bytes diretamente do upstream para o cliente sem materialização em memória.

---

## Features

| Feature | Descrição |
|---------|-----------|
| **Proxy Reverso** | Encaminha requests HTTP para backends configuráveis via `adapter.yaml` |
| **Regras Groovy** | Motor de regras dinâmicas com hot-reload (recompilação a cada 60s) |
| **OAuth2 Client** | Interceptor OkHttp que injeta `Bearer` token automaticamente nos backends |
| **JWT Validation** | Validação de tokens JWT com suporte a JWKS (Auth0 java-jwt) |
| **Distributed Tracing** | Brave/Zipkin com 11+ spans semânticos por request |
| **Propagação B3** | Extração de contexto B3 na entrada e injeção nos requests upstream |
| **Respostas Sintéticas** | Scripts Groovy podem gerar respostas mock (`createSynthResponse`) |
| **Response Processors** | Closures Groovy para pós-processamento da resposta upstream |
| **Streaming** | Modo `returnPipe` para transferência zero-copy de large payloads |
| **Múltiplos Listeners** | Portas independentes com configurações de segurança distintas |
| **Connection Pooling** | OkHttp ConnectionPool + Dispatcher com Virtual Threads (Java 21) |
| **Async Logging** | Log4j2 + LMAX Disruptor para logging fora do hot path |
| **Cluster Mode** | NGrid mesh TCP com leader election e DistributedMap para coordenação entre instâncias |
| **Token Sharing** | Tokens OAuth2 compartilhados via POW-RBL (Publish-on-write + Read-before-login) |
| **Rules Deploy** | Deploy atômico de scripts Groovy via Admin API (`POST /admin/rules/deploy`) com replicação cluster |
| **Circuit Breaker** | Resilience4j por backend — CLOSED/OPEN/HALF_OPEN com métricas Micrometer |
| **Rate Limiting** | Controle de taxa por listener, rota e backend com modos `stall` (delay) e `nowait` (429 imediato) |
| **Métricas Prometheus** | Counters/timers inbound e upstream via `/actuator/prometheus` |
| **Health Check** | Spring Boot Actuator com status de cluster, circuit breaker e instance ID |

---

## Arquitetura

```
                    ┌─────────────────────────────────────────┐
 Clients ──────────▶│              n-gate                     │
                    │                                         │
                    │  Javalin 7 (Jetty 12)                   │
                    │  ├── Listener :9090 (secured)           │
                    │  └── Listener :9091 (open)              │
                    │                                         │
                    │  ┌──────────┐   ┌────────────────────┐  │
                    │  │ JWT/OAuth │   │  Groovy Rules      │  │
                    │  │ Decoder   │   │  Engine            │  │
                    │  └──────────┘   └────────────────────┘  │
                    │                                         │
                    │  ┌──────────────────────────────────┐   │
                    │  │  OkHttp 4 (Virtual Threads)      │   │
                    │  │  ConnectionPool + OAuth Intercept │   │
                    │  └──────────────────────────────────┘   │
                    └──────┬──────────┬──────────┬────────────┘
                           │          │          │
                     Backend A   Backend B   Backend N
                                         
                    ──── Brave/Zipkin Tracing ────
```

![Arquitetura n-gate](https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/n-gate/main/docs/diagrams/architecture.puml)

### Topologia Cluster

```
                     ┌─────────────────────────────────────────────┐
 Client ────────────▶│          nginx Load Balancer (:5080)        │
                     └──────┬──────────┬──────────┬───────────────┘
                            │          │          │
                     ┌──────▼───┐ ┌────▼─────┐ ┌─▼────────┐
                     │ n-gate-1 │ │ n-gate-2 │ │ n-gate-3 │
                     │  :9091   │ │  :9091   │ │  :9091   │
                     │  :9190   │ │  :9190   │ │  :9190   │
                     │  :7100 ◄─┼─► :7100 ◄─┼─► :7100   │
                     └──────┬───┘ └────┬─────┘ └─┬────────┘
                            │   NGrid Mesh (TCP) │
                            │          │          │
                     ┌──────▼──────────▼──────────▼──────────┐
                     │  DistributedMap: tokens + rules       │
                     └───────────────────────────────────────┘
```

![Topologia Cluster](https://uml.nishisan.dev/proxy?src=https://raw.githubusercontent.com/nishisan-dev/n-gate/main/docs/diagrams/cluster_topology.puml)

Para detalhes, veja [docs/architecture.md](docs/architecture.md).

---

## Quick Start

### Pré-requisitos

- Docker
- Docker Compose (plugin `docker compose`)

### Subir o ambiente (standalone)

```bash
docker compose up --build
```

Background:

```bash
docker compose up --build -d
```

### Subir como Cluster (3 nós + LB)

```bash
docker compose -f docker-compose.yml -f docker-compose.cluster.yml up --build -d
```

Para bench mode (sem tracing, log INFO):

```bash
docker compose -f docker-compose.yml -f docker-compose.bench.yml -f docker-compose.cluster.yml up --build -d
```

Parar:

```bash
docker compose down
```

### Portas dos serviços

| Serviço | Porta | Descrição |
|---------|:-----:|-----------| 
| `n-gate` | `9090` | Proxy principal (com auth OAuth ao upstream) |
| `n-gate` | `9091` | Proxy benchmark (sem auth, upstream estático) |
| `n-gate` | `9190` | Actuator (health, prometheus, admin API) |
| `keycloak` | `8081` | SSO / Identity Provider |
| `zipkin` | `9411` | Distributed Tracing UI |
| `static-backend` | `3080` | Nginx com JSON fixo (benchmark) |
| `benchmark-ui` | `8000` | UI web para benchmarks |
| **Cluster Mode** | | |
| `nginx-cluster-lb` | `5080` | LB round-robin → 3 nós n-gate |
| `ngate-node1` | `9191` | Nó 1 proxy (listener http-noauth) |
| `ngate-node2` | `9192` | Nó 2 proxy |
| `ngate-node3` | `9193` | Nó 3 proxy |
| `ngate-node*` | `7100` | NGrid mesh (interno, inter-nó) |

### Teste rápido

```bash
# Verificar Keycloak
curl -s http://localhost:8081/realms/inventory-dev/.well-known/openid-configuration | jq .issuer

# Proxy básico
curl -i http://localhost:9090/realms/inventory-dev/.well-known/openid-configuration

# Backend estático (benchmark)
curl -i http://localhost:9091/qualquer/path
```

### Obter token OAuth

```bash
TOKEN=$(curl -s -X POST 'http://localhost:8081/realms/inventory-dev/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=ngate-client' \
  -d 'client_secret=ngate-secret' \
  -d 'username=inventory-svc' \
  -d 'password=inventory-svc-pass' | jq -r .access_token)

curl -i 'http://localhost:9090/realms/inventory-dev/protocol/openid-connect/userinfo' \
  -H "Authorization: Bearer ${TOKEN}"
```

### Tracing (Zipkin)

- Acesse: `http://localhost:9411`
- Serviço: `http`

---

## Profiles

| Profile | Log Level | Tracing | Uso |
|---------|-----------|---------|-----|
| `dev` (padrão) | `DEBUG` | ✅ Habilitado | Desenvolvimento |
| `bench` | `INFO` | ❌ Desabilitado | Benchmark prod-like |

```bash
# Dev (padrão)
docker compose up -d

# Bench
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d

# Cluster (3 nós NGrid + LB)
docker compose -f docker-compose.yml -f docker-compose.cluster.yml up -d

# Cluster + Bench
docker compose -f docker-compose.yml -f docker-compose.bench.yml -f docker-compose.cluster.yml up -d
```

---

## Benchmark

```bash
# Instalar Apache Bench
sudo apt install apache2-utils

# Executar benchmark
python3 scripts/benchmark.py
```

O script faz warmup, roda testes com concorrência 1/10/50, e gera relatório comparativo.

---

## Documentação

| Documento | Conteúdo |
|-----------|----------|
| [Arquitetura](docs/architecture.md) | Componentes internos, cluster mode, fluxo de request, modelo de threading |
| [Configuração](docs/configuration.md) | Referência completa do `adapter.yaml` (cluster, admin, circuit breaker) |
| [Regras Groovy](docs/groovy_rules.md) | Como escrever regras, API, exemplos práticos |
| [Segurança](docs/security.md) | JWT, OAuth2, políticas de autenticação |
| [Casos de Uso](docs/use_cases.md) | Cenários end-to-end com configuração e comandos |
| [Observabilidade](docs/observability.md) | Spans, tracing, métricas Prometheus, circuit breaker |
| [Rate Limiting](docs/rate-limiting.md) | Modos stall/nowait, zonas, configuração por escopo, métricas |
| [Testes de Cluster](docs/cluster_integration_tests.md) | Testes de integração Docker do cluster NGrid |

---

## Tech Stack

| Componente | Versão | Função |
|------------|--------|--------|
| Java (OpenJDK) | 21 | Runtime com Virtual Threads |
| Spring Boot | 3.5.6 | Contexto, configuração e Actuator |
| Javalin | 7.0.1 | HTTP Framework (Jetty 12) |
| OkHttp | 4.12.0 | HTTP Client para backends |
| Groovy | 3.0.12 | Motor de regras dinâmicas |
| NGrid (nishi-utils) | 3.1.0 | Cluster: mesh TCP, leader election, DistributedMap |
| Resilience4j | 2.2.0 | Circuit breaker para proteção de backends |
| Micrometer | — | Métricas Prometheus (via Spring Boot Actuator) |
| Brave | 6.0.3 | Instrumentação de tracing |
| Zipkin Reporter | 3.4.0 | Envio assíncrono de spans |
| Log4j2 | — | Logging com LMAX Disruptor |
| Auth0 java-jwt | 4.4.0 | Decodificação JWT |
| Auth0 jwks-rsa | 0.22.1 | Validação JWKS |
| Jackson | 2.19.2 | Parse YAML (config) |
| Guava | 33.3.1 | Cache para transient clients |

---

## Estrutura do Projeto

```
n-gate/
├── config/
│   ├── adapter.yaml           # Configuração principal (standalone)
│   └── adapter-cluster.yaml   # Configuração cluster (3 nós)
├── rules/
│   └── default/
│       └── Rules.groovy       # Script Groovy de regras
├── custom/                    # Scripts de decoders customizados
├── compose/                   # Configs Docker (Keycloak, Nginx, etc.)
│   └── nginx-cluster/         # Config nginx LB para cluster
├── docs/                      # Documentação técnica
│   ├── diagrams/              # Diagramas PlantUML (C4)
│   ├── architecture.md
│   ├── configuration.md
│   ├── cluster_integration_tests.md
│   ├── groovy_rules.md
│   ├── security.md
│   ├── use_cases.md
│   └── observability.md
├── scripts/
│   └── benchmark.py           # Script de benchmark
├── src/main/java/dev/nishisan/ngate/
│   ├── auth/                  # JWT, OAuth, Token Decoders
│   ├── cluster/               # ClusterService (NGrid lifecycle)
│   ├── configuration/         # POJOs de configuração (adapter.yaml)
│   ├── groovy/                # Bindings protegidos para Groovy
│   ├── http/                  # Core: proxy, workload, adapters
│   │   ├── circuit/           # BackendCircuitBreakerManager
│   │   └── ratelimit/         # RateLimitManager, RateLimitResult
│   ├── manager/               # Gerenciadores de config e endpoints
│   └── observabitliy/         # TracerService, SpanWrapper, ProxyMetrics
├── ssl/                       # Keystores SSL
├── docker-compose.yml         # Ambiente dev (standalone)
├── docker-compose.bench.yml   # Override benchmark
├── docker-compose.cluster.yml # Cluster: 3 nós + nginx LB
└── pom.xml                    # Maven build
```

---

## Licença

Este projeto é distribuído sob a licença [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
