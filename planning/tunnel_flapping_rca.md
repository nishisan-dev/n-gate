# Tunnel Mode: Análise de Causa Raiz (RCA) — Flapping de Membros

## Problema Observado

Os membros registrados no tunnel (ishin-1, ishin-2) entravam em **flapping contínuo**: eram adicionados ao
`VirtualPortGroup` pelo poller, removidos pelo keepalive checker 1 segundo depois, e readicionados na próxima
iteração do poller — em um ciclo infinito de ~1s. Isso resultava em:
- Listener da porta 9090 abrindo e fechando continuamente
- Topologia oscilando (membros aparecem e somem na API `/api/v1/topology`)
- Impossibilidade de estabelecer conexões TCP via tunnel

## Causa Raiz

O campo `lastKeepAlive` no `TunnelRegistryEntry` armazenado no `DistributedMap` (NGrid) ficava
permanentemente **stale** (congelado no timestamp do registro inicial).

### Por quê?

O modelo de replicação do NGrid `DistributedMap` é **leader-based**:
- Apenas o **leader** processa writes (`put()`) e replica aos followers.
- Quando um **follower** (proxy ishin-1/ishin-2) chama `registryMap.put()`, o put completa
  **sem lançar exceção**, mas o valor **não é propagado de volta ao leader**.
- O leader mantém a versão original (do registro inicial), com `lastKeepAlive` fixo.

### Fluxo do Bug

```
1. Proxy faz put() no startup    → lastKeepAlive = T₀ (timestamp atual)
2. Leader armazena               → entry com lastKeepAlive = T₀
3. Proxy keepalive (a cada 3s)   → localEntry.setLastKeepAlive(T₁)
                                 → registryMap.put() → completa sem exceção
                                 → MAS o valor NÃO chega ao leader!
4. Tunnel poller (a cada 1s)     → registryMap.get() → retorna entry com lastKeepAlive = T₀
5. Keepalive checker             → T_atual - T₀ > threshold (9s)
                                 → Remove membro por timeout
6. Próximo poll                  → Relê entry → Re-adiciona membro (lastKeepAlive = T₀ ainda)
7. Keepalive checker             → Remove novamente
8. ... ciclo infinito (flap)
```

### Evidências nos Logs

```
Poll read tunnel:registry:ishin-1 — lastKeepAlive=30638ms ago    ← STALE (fixo)
Poll read tunnel:registry:ishin-2 — lastKeepAlive=30808ms ago    ← STALE (fixo)
Keepalive timeout — removing ishin-1:19090 (last keepalive: 31664ms ago)
Member removed from group vPort:9090
New VirtualPortGroup created for port 9090        ← re-criado
Member added to group vPort:9090                  ← readicionado
Keepalive timeout — removing ishin-1:19090        ← removido de novo (1s depois)
```

## Solução

**Proof-of-life via polling**: O poller usa `System.currentTimeMillis()` ao criar o `BackendMember`,
ao invés de usar o campo `lastKeepAlive` serializado do `TunnelRegistryEntry`. O fato de o poller
conseguir ler com sucesso a entry do `DistributedMap` é prova suficiente de que o proxy está vivo
(o entry existe e está acessível).

```java
// ANTES (broken):
new BackendMember(..., registryEntry.getLastKeepAlive(), ...);

// DEPOIS (fix):
new BackendMember(..., System.currentTimeMillis(), ...);
```

A remoção por keepalive timeout agora só acontece se o **entry desaparecer do DistributedMap**
(proxy desligou ou foi removido do cluster), que é o comportamento correto.

## Lição Aprendida

O `DistributedMap.put()` do NGrid em um nó follower **não garante propagação ao leader**.
Para dados que precisam ser atualizados frequentemente por followers (como keepalives), não se
deve depender de campos internos do entry serializado — use mecanismos de proof-of-life
baseados na presença/acessibilidade do dado.
