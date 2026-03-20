# Bug Fix: Tunnel L4 — Autodiscovery Dinâmico de Registry Keys

## Contexto

No Docker Compose, o tunnel L4 não abre o listener TCP na porta 9090. A causa raiz é a **lógica de pré-seed estático** no `TunnelService.onStartup()` (linhas 126-142): ela depende de `config.getCluster().getSeeds()` para derivar os `nodeId` dos proxies e popular `knownRegistryKeys` no `TunnelRegistry`. Essa abordagem é frágil porque:

1. Assume que os hostnames dos seeds são iguais aos `nodeId` dos proxies
2. Roda uma única vez no startup — se o timing no Docker faz com que a lógica falhe, não há retry
3. O `DistributedMap` (NGrid) não expõe `keys()`/`entrySet()`, então o poller depende 100% dessas keys pré-populadas

> [!IMPORTANT]
> A solução definitiva substitui o pré-seed estático por **autodiscovery dinâmico** — o `TunnelRegistry` gera registry keys candidatas a partir dos peers ativos do cluster NGrid a cada ciclo de polling.

## Proposed Changes

### Cluster — Expor Peer Node IDs

#### [MODIFY] [ClusterService.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/cluster/ClusterService.java)

Adicionar método `getClusterPeerNodeIds()` que retorna os `nodeId` de todos os peers configurados nos seeds do cluster (excluindo o nó local). Isso é derivado da mesma config de seeds usada no `buildNGridConfig()`.

```java
/**
 * Retorna os nodeIds dos peers do cluster (excluindo self).
 * Derivados do bloco cluster.seeds do adapter.yaml.
 */
public List<String> getClusterPeerNodeIds() {
    if (!isClusterMode()) return List.of();
    ClusterConfiguration clusterConfig = configurationManager.loadConfiguration().getCluster();
    if (clusterConfig == null || clusterConfig.getSeeds() == null) return List.of();

    List<String> peerIds = new ArrayList<>();
    for (String seed : clusterConfig.getSeeds()) {
        String[] parts = seed.split(":");
        if (parts.length >= 1) {
            String seedNodeId = parts[0];
            if (!seedNodeId.equals(localNodeId)) {
                peerIds.add(seedNodeId);
            }
        }
    }
    return peerIds;
}
```

---

### Tunnel — Autodiscovery Dinâmico

#### [MODIFY] [TunnelService.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/TunnelService.java)

1. **Passar `ClusterService` ao `TunnelRegistry`** para que ele possa consultar peers dinamicamente
2. **Remover a lógica de pré-seed estático** (linhas 126-142) — substituída pela autodiscovery no poller

```diff
-        // Inicializar TunnelRegistry
-        this.tunnelRegistry = new TunnelRegistry(tunnelConfig, tunnelMetrics);
+        // Inicializar TunnelRegistry com autodiscovery via ClusterService
+        this.tunnelRegistry = new TunnelRegistry(tunnelConfig, tunnelMetrics, clusterService);
         this.tunnelRegistry.setRegistryMap(registryMap);
```

```diff
-        // Pré-popular knownRegistryKeys a partir dos seeds do cluster.
-        // O DistributedMap (NGrid) não expõe keys()/entrySet(), então o poller
-        // precisa saber quais chaves verificar. Derivamos dos seeds: cada hostname
-        // do seed é o nodeId do proxy, e a chave é "tunnel:registry:" + nodeId.
-        if (config.getCluster() != null && config.getCluster().getSeeds() != null) {
-            String localNodeId = clusterService.getLocalNodeId();
-            for (String seed : config.getCluster().getSeeds()) {
-                String[] parts = seed.split(":");
-                String seedNodeId = parts[0];
-                // Excluir o próprio nó tunnel (ele não se registra como proxy)
-                if (!seedNodeId.equals(localNodeId)) {
-                    String registryKey = TunnelRegistry.REGISTRY_KEY_PREFIX + seedNodeId;
-                    tunnelRegistry.addKnownRegistryKey(registryKey);
-                    logger.info("Pre-seeded registry key for polling: {}", registryKey);
-                }
-            }
-        }
```

#### [MODIFY] [TunnelRegistry.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/gateway/tunnel/TunnelRegistry.java)

1. **Receber `ClusterService` no construtor**
2. **No início de cada `pollRegistry()`**, gerar registry keys candidatas a partir dos peers do cluster e adicioná-las automaticamente ao `knownRegistryKeys`
3. Manter `addKnownRegistryKey()` público para uso pelo `TunnelRegistrationService`

```java
private final ClusterService clusterService;

public TunnelRegistry(TunnelConfiguration config, TunnelMetrics metrics, ClusterService clusterService) {
    this.config = config;
    this.loadBalancer = TunnelLoadBalancer.forAlgorithm(config.getLoadBalancing());
    this.metrics = metrics;
    this.clusterService = clusterService;
}
```

No `pollRegistry()`, antes de iterar sobre `keysSnapshot`:

```java
private void pollRegistry() {
    if (registryMap == null) return;

    // Autodiscovery: derivar registry keys candidatas dos peers do cluster
    if (clusterService != null) {
        for (String peerNodeId : clusterService.getClusterPeerNodeIds()) {
            String candidateKey = REGISTRY_KEY_PREFIX + peerNodeId;
            if (knownRegistryKeys.add(candidateKey)) {
                logger.info("Auto-discovered registry key from cluster peer: {}", candidateKey);
            }
        }
    }

    // ... restante do polling (sem mudanças)
}
```

## Verification Plan

### Testes Automatizados

1. **Build + Testes unitários existentes** — garante que nada quebrou:
   ```bash
   cd /home/lucas/Projects/ishin-gateway
   mvn clean compile -q
   mvn test -pl . -q
   ```

2. **Testes de tunnel existentes** — já cobrem `VirtualPortGroup`, `TunnelLoadBalancer`, `TunnelMetrics`:
   ```bash
   mvn test -pl . -Dtest="VirtualPortGroupTest,TunnelLoadBalancerTest,TunnelMetricsTest" -q
   ```

### Teste Manual — Docker Compose

> [!NOTE]
> Este é o teste principal de validação do bug fix. Requer que a imagem Docker seja reconstruída com as mudanças.

1. Reconstruir a imagem Docker:
   ```bash
   cd /home/lucas/Projects/ishin-gateway
   mvn clean package -DskipTests -q
   docker build -t lnishisan/ishin-gateway:latest .
   ```

2. Subir o lab Docker:
   ```bash
   cd ishin-gateway-test-case/docker/
   docker compose down -v
   docker compose up -d
   ```

3. Aguardar ~40s e validar:
   ```bash
   # Deve mostrar "Auto-discovered registry key from cluster peer"
   docker compose logs tunnel-1 | grep -i "auto-discovered"

   # Deve mostrar "VirtualPortGroup created" e "Listener opened"
   docker compose logs tunnel-1 | grep -iE "virtualportgroup created|listener opened"

   # Deve mostrar porta 9090 em LISTEN
   docker compose exec tunnel-1 cat /proc/net/tcp | grep "2382"

   # Teste funcional: curl via tunnel
   curl -s http://localhost:9090/health
   ```

4. Cleanup:
   ```bash
   docker compose down -v
   ```
