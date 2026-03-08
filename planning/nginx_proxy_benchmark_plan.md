# Planejamento: Adição do Nginx Proxy Reverso para Benchmark

## Visão Geral

O objetivo é adicionar um novo container Nginx (`nginx-proxy`) ao ecossistema do `docker-compose.yml` para atuar exclusivamente como um proxy reverso para o `static-backend`. Esta alteração vai permitir um comparativo "maçã com maçã" entre o Nginx atuando como proxy reverso e o `inventory-adapter` (Javalin) agindo no mesmo papel, medindo qual introduz maior overhead na chamada ao backend "cru".

## User Review Required

Revisão técnica solicitada:
- **Portas:** A porta `4080` será mapeada no host para o novo nginx-proxy. (3080 = backend direto, 4080 = nginx-proxy, 9090 = javalin-proxy).
- **Configuração:** O arquivo `compose/nginx-proxy/default.conf` passará os cabeçalhos padrão (`Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto`).

## Proposed Changes

### Componente: Infraestrutura Docker Compose
Adicionar o serviço `nginx-proxy` que dependerá do `static-backend`.

#### [MODIFY] docker-compose.yml
Adição do bloco:
```yaml
  nginx-proxy:
    image: nginx:alpine
    volumes:
      - ./compose/nginx-proxy/default.conf:/etc/nginx/conf.d/default.conf:ro
    ports:
      - "4080:8080"
    depends_on:
      - static-backend
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1:8080/health"]
      interval: 5s
      timeout: 2s
      retries: 3
```

#### [NEW] compose/nginx-proxy/default.conf
Criar configuração para o Nginx rodar em modo proxy pass:
```nginx
server {
    listen 8080;
    server_name _;

    location / {
        proxy_pass http://static-backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Mantendo o healthcheck
    location /health {
        return 200 '{"status":"healthy"}';
    }
}
```

### Componente: Scripts

#### [MODIFY] scripts/benchmark.py
Atualizar o script de benchmark para incluir o novo proxy Nginx nos cenários de teste, adaptando a lógica para avaliar um array com os 3 endpoints em vez de somente dois:

1. **Alteração nas constantes para receber uma URL adicional (nginx_proxy)**
```python
# ─── Configuração ────────────────────────────────────────────────────────────

BASELINE_URL = "http://localhost:3080/"
NGINX_PROXY_URL = "http://localhost:4080/"
JAVALIN_PROXY_URL = "http://localhost:9091/"

ENDPOINTS = [
    ("baseline", "Baseline (Nginx Direto)", BASELINE_URL),
    ("nginx_proxy", "Proxy (Nginx -> Nginx)", NGINX_PROXY_URL),
    ("javalin_proxy", "Proxy (Javalin -> Nginx)", JAVALIN_PROXY_URL)
]
```

2. **Refatoração da Lógica de Run e Report**
Modificar as fases de `Warmup`, `Fase 1` (Requests) e `Fase 2` (Tempo), alterando de chamadas fixas de baseline e proxy para laços sequenciais validando a matriz `ENDPOINTS`. A impressão do relatório também exibirá comparativos de overhead para cada proxy comparado ao baseline.

## Verification Plan

### Automated Tests
- Validar se o `docker compose up -d` ou `docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d` sobe o ambiente resolvendo todas as portas (3080, 4080, 8081, 9411, 9090, 9091, 18080).

### Manual Verification
- Fazer uma requisição ao proxy nginx: `curl http://localhost:4080` e garantir que retorne a carga nativa do `static-backend`.
- Comparar rodando teste de carga com o `ab`:
  - `ab -c 100 -n 5000 http://localhost:4080/`
  - `ab -c 100 -n 5000 http://localhost:9090/`
