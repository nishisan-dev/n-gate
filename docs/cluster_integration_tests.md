# Testes de Integração: Cluster Mode NGrid

## Visão Geral

O n-gate suporta um modo cluster opcional via [NGrid](https://github.com/nishisan-dev/nishi-utils), permitindo múltiplas instâncias coordenarem estado distribuído (tokens OAuth, leader election, health). Os testes de integração validam este comportamento com **2 nós reais** rodando em containers Docker efêmeros.

## Stack de Teste

| Componente | Tecnologia | Versão |
|---|---|---|
| Orquestração de containers | [Testcontainers](https://testcontainers.org) | 2.0.3 |
| Framework de teste | JUnit 5 | via Spring Boot |
| Polling assíncrono | [Awaitility](https://github.com/awaitility/awaitility) | 4.2.2 |
| HTTP Client | OkHttp | 4.12.0 |
| Backend mock | Nginx Alpine | latest |
| IdP (OAuth) | Keycloak | 26.0 |

## Topologia dos Testes

### Cluster Básico (`NGridClusterIntegrationTest`)

```
                    ┌────────────────────┐
                    │  Docker Network    │
                    │  (isolada/efêmera) │
                    │                    │
                    │  ┌──────────────┐  │
                    │  │ mock-backend │  │
                    │  │ (nginx:8080) │  │
                    │  └──────┬───────┘  │
                    │         │          │
                    │    ┌────┴────┐     │
                    │    │         │     │
                    │  ┌─┴──┐  ┌──┴─┐   │
                    │  │ N1 │  │ N2 │   │
                    │  │9091│  │9091│   │
                    │  │9190│  │9190│   │
                    │  │7100│◄─►7100│   │
                    │  └────┘  └────┘   │
                    │    NGrid Mesh     │
                    └────────────────────┘
```

### Cluster com OAuth (`NGridClusterOAuthIntegrationTest`)

```
              ┌──────────────────────────────┐
              │  Docker Network (isolada)     │
              │                              │
              │  ┌──────────┐ ┌───────────┐  │
              │  │ Keycloak │ │ mock-bknd │  │
              │  │  :8080   │ │   :8080   │  │
              │  └────┬─────┘ └─────┬─────┘  │
              │       │ OAuth       │ HTTP   │
              │   ┌───┴─────────────┴───┐    │
              │   │                     │    │
              │ ┌─┴──┐              ┌──┴─┐  │
              │ │ N1 │    NGrid     │ N2 │  │
              │ │9091│◄────────────►│9091│  │
              │ │7100│  DistMap     │7100│  │
              │ └────┘  (tokens)    └────┘  │
              └──────────────────────────────┘
```

- **N1/N2**: Instâncias n-gate com cluster mode + OAuth habilitados
- **Keycloak**: IdP com realm `inventory-dev`, client `ngate-client`
- **DistMap (tokens)**: `DistributedMap<String, SerializableTokenData>` do NGrid

## Cenários de Teste

### Cluster Básico (sem OAuth) — `NGridClusterIntegrationTest`

| # | Teste | Descrição |
|---|-------|-----------|
| T1 | Mesh Formation | 2 nós formam mesh NGrid (`activeMembers: 2`) |
| T2 | Leader Election | Exatamente 1 líder (XOR validation) |
| T3 | Proxy Funcional | HTTP 200 em ambos os nós via mock backend |
| T4 | Instance ID | `instanceId` distinto por nó no health |
| T5 | Graceful Shutdown | Nó 2 para, Nó 1 continua com `activeMembers ≤ 1` |

### Token Sharing OAuth (com Keycloak) — `NGridClusterOAuthIntegrationTest`

| # | Teste | Descrição |
|---|-------|-----------|
| T6 | Token Sharing POW-RBL | Nó 1 obtém token do Keycloak → publica no `DistributedMap` → Nó 2 lê do mapa sem ir ao IdP |
| T7 | Resiliência na Queda | Nó 1 cai → Nó 2 continua servindo com token válido (cache local + DistributedMap) |

> **POW-RBL** = Publish-on-write + Read-before-login. Qualquer nó que obtém um token publica no mapa distribuído. Antes de ir ao IdP, cada nó verifica se existe token válido no mapa.

---

## Como Executar

### Pré-requisitos

1. **Docker** rodando (versão ≥ 20.10, testado com 29.3.0)
2. **`~/.m2/settings.xml`** com credenciais do GitHub Packages (para resolver `nishi-utils`)
3. **Portas livres**: nenhuma porta fixa necessária (Testcontainers usa portas dinâmicas)
4. **Espaço em disco**: ~500MB para imagens Docker (n-gate, Nginx, Keycloak, Ryuk)

### Comandos

```bash
# ─── Executar TODOS os testes de cluster ───────────────────────
mvn -s ~/.m2/settings.xml test -Dtest="NGridCluster*IntegrationTest"

# ─── Executar apenas testes básicos (sem OAuth, mais rápido) ───
mvn -s ~/.m2/settings.xml test -Dtest="NGridClusterIntegrationTest"
# Tempo esperado: ~80s

# ─── Executar apenas testes OAuth (com Keycloak) ──────────────
mvn -s ~/.m2/settings.xml test -Dtest="NGridClusterOAuthIntegrationTest"
# Tempo esperado: ~120s (Keycloak leva ~30s para subir)

# ─── Executar com logs verbose (debug) ────────────────────────
mvn -s ~/.m2/settings.xml test -Dtest="NGridCluster*IntegrationTest" \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=debug
```

> **Nota:** Na primeira execução, o Docker buildará a imagem n-gate via Dockerfile multi-stage (~2-3 min). Execuções subsequentes usam cache (~20s de build).

### Interpretando os Logs

Os logs dos containers são prefixados para fácil identificação:

| Prefixo | Container |
|---------|-----------|
| `[node-1]` | n-gate Nó 1 (cluster básico) |
| `[node-2]` | n-gate Nó 2 (cluster básico) |
| `[oauth-node-1]` | n-gate Nó 1 (cluster OAuth) |
| `[oauth-node-2]` | n-gate Nó 2 (cluster OAuth) |
| `[keycloak]` | Keycloak IdP |

**Mensagens-chave nos logs:**

```
# Mesh NGrid formado
"NGrid cluster started — nodeId: [xxx]"
"Leadership change — isLeader: [true/false]"

# Token Sharing POW-RBL
"Token [test-keycloak] published to cluster DistributedMap"    ← publicou
"Token [test-keycloak] loaded from cluster DistributedMap"     ← leu do mapa
"Sent New Token: [xxx***]"                                      ← login no IdP
```

---

## Troubleshooting

### Docker API incompatível

```
client version 1.32 is too old. Minimum supported API version is 1.40
```

**Causa:** Testcontainers 1.x usa API Docker antiga. **Solução:** Usar Testcontainers ≥ 2.0.x (já configurado).

### Permissões no `target/`

```
AccessDeniedException: target/test-classes/...
```

**Causa:** Docker compose anterior criou arquivos como `root` no `target/`.
**Solução:**
```bash
docker run --rm -v $(pwd)/target:/target alpine chown -R $(id -u):$(id -g) /target
```

### Timeout na formação do mesh

Se os testes falham com `ConditionTimeout` no mesh formation:
- Verifique se o Docker tem ≥ 2GB RAM disponível
- Em CI, aumente os timeouts no teste (de 90s para 120s)
- Confirme que a rede Docker não está bloqueando tráfego inter-container

### Keycloak lento para subir

Se os testes OAuth falham no startup do Keycloak:
- Keycloak 26.0 pode levar até 60s no primeiro start
- O `startupTimeout` já está em 120s — suficiente para CI

---

## Arquivos Envolvidos

| Arquivo | Propósito |
|---|---|
| [NGridClusterIntegrationTest.java](../src/test/java/dev/nishisan/ngate/cluster/NGridClusterIntegrationTest.java) | Testes T1-T5 (cluster básico) |
| [NGridClusterOAuthIntegrationTest.java](../src/test/java/dev/nishisan/ngate/cluster/NGridClusterOAuthIntegrationTest.java) | Testes T6-T7 (OAuth token sharing) |
| [Dockerfile](../Dockerfile) | Build multi-stage da imagem n-gate |
| [adapter-test-cluster.yaml](../src/test/resources/adapter-test-cluster.yaml) | Config cluster sem OAuth |
| [adapter-test-cluster-oauth.yaml](../src/test/resources/adapter-test-cluster-oauth.yaml) | Config cluster com OAuth + Keycloak |
| [application-test.properties](../src/test/resources/application-test.properties) | Profile Spring Boot para testes |
| [mock-backend.conf](../src/test/resources/testcontainers/mock-backend.conf) | Config Nginx do backend mock |
| [realm-inventory-dev.json](../src/test/resources/testcontainers/realm-inventory-dev.json) | Realm Keycloak para import |

## Decisões de Design

1. **`GenericContainer` sobre `DockerComposeContainer`**: Controle individual sobre lifecycle de cada nó
2. **Sem OAuth nos testes básicos**: Os cenários T1-T5 focam no cluster NGrid — rápido e sem dependência do Keycloak
3. **Awaitility sobre `Thread.sleep`**: Polling com backoff evita flakiness em CI
4. **`ImageFromDockerfile`**: Build a partir do Dockerfile real, garantindo paridade com produção
5. **Race condition no T6 é aceitável**: O POW-RBL não garante que apenas 1 nó fale com o IdP — ambos podem fazer login quase simultaneamente. O teste aceita ambos os cenários (ideal: leu do mapa; aceitável: fez login próprio)
