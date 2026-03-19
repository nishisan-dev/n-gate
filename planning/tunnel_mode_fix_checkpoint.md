# Checkpoint: Fix Tunnel Mode Test Case

**Data:** 2026-03-19  
**Status:** ✅ Resolvido — topologia estável, zero flapping

---

## Resumo dos Problemas e Fixes

### Fix 1 — Crash no startup (TunnelRegistrationService) ✅
- **Causa:** `registryMap.put()` síncrono no `onStartup()` falhava quando o NGrid ainda não tinha líder estável
- **Fix:** Método `publishWithRetry()` com backoff exponencial (5 tentativas, delay 2s → 30s)

### Fix 2 — Poller sem registry keys (TunnelService) ✅
- **Causa:** `TunnelRegistry.pollRegistry()` iterava sobre `knownRegistryKeys` (vazia), pois o NGrid `DistributedMap` não expõe `keys()`
- **Fix:** `TunnelService.onStartup()` pré-popula `knownRegistryKeys` derivando nodeIds dos seeds do cluster

### Fix 3 — keysToForget prematura (TunnelRegistry) ✅
- **Causa:** Na primeira poll, entries ainda não existiam → chaves removidas de `knownRegistryKeys` permanentemente
- **Fix:** Removida lógica `keysToForget` — chaves ausentes são apenas ignoradas, poller retenta

### Fix 4 — Host registrado como localhost (TunnelRegistrationService) ✅
- **Causa:** `resolveHost()` usava `InetAddress.getLocalHost()` → `127.0.2.1`
- **Fix:** Usa `config.getCluster().getHost()` (ex: `192.168.56.11`) — o IP real da rede

### Fix 5 — lastKeepAlive stale / flapping (TunnelRegistry) ✅
- **Causa:** `DistributedMap.put()` num follower NGrid completa sem exceção mas NÃO propaga `lastKeepAlive` ao leader. O poller lia o campo serializado stale, keepalive checker removia membros ciclicamente.
- **Fix:** Poller usa `System.currentTimeMillis()` como `lastKeepAlive` ao criar `BackendMember` — ler com sucesso do DistributedMap é proof-of-life

### Fix bônus — Keepalive loop resiliente (TunnelRegistrationService) ✅
- Separou catch de `IllegalStateException` (WARN silencioso) do catch genérico (ERROR)

---

## Validação

- 3 consultas consecutivas à API `/api/v1/topology` (0s, 5s, 10s): **topologia estável, zero flapping**
- IPs corretos: ishin-1=192.168.56.11, ishin-2=192.168.56.12
- Keepalive ages: 0.2-0.3s (< 1s — muito fresco)
- vPort:9090 com listener aberto e 2 membros ACTIVE

## Arquivos Modificados

| Arquivo | Fixes |
|---------|-------|
| `TunnelRegistrationService.java` | Fix 1, Fix 4, Fix 6 |
| `TunnelService.java` | Fix 2 |
| `TunnelRegistry.java` | Fix 3, Fix 5 |
