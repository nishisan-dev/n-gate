# Inventory Adapter - Dev Local

Este projeto está preparado para rodar localmente com um único `docker compose`, subindo:

- `inventory-adapter`
- `keycloak` (SSO)
- `zipkin` (tracing)
- `static-backend` (nginx estático para benchmark)

## Pré-requisitos

- Docker
- Docker Compose (plugin `docker compose`)

## Subir o ambiente

```bash
docker compose up --build
```

Para rodar em background:

```bash
docker compose up --build -d
```

Para parar e remover containers:

```bash
docker compose down
```

## Portas dos serviços

| Serviço | Porta Host | Porta Container | Descrição |
|---------|:----------:|:---------------:|-----------|
| `inventory-adapter` | `9090` | `9090` | Proxy principal (com auth OAuth ao upstream) |
| `inventory-adapter` | `9091` | `9091` | Proxy benchmark (sem auth, upstream estático) |
| `inventory-adapter` | `18080` | `18080` | Spring Boot / diagnóstico |
| `keycloak` | `8081` | `8080` | SSO / Identity Provider |
| `zipkin` | `9411` | `9411` | Distributed Tracing UI |
| `static-backend` | `3080` | `8080` | Nginx com JSON fixo (benchmark only) |

## Fluxo de teste (fim a fim)

### 1. Validar que o Keycloak subiu com o realm

```bash
curl -s http://localhost:8081/realms/inventory-dev/.well-known/openid-configuration | jq .issuer
```

Resultado esperado: issuer com `http://localhost:8081/realms/inventory-dev`.

### 2. Validar proxy básico no Adapter

O adapter está configurado para usar o Keycloak como backend padrão (`config/adapter.yaml`).

```bash
curl -i http://localhost:9090/realms/inventory-dev/.well-known/openid-configuration
```

Se responder `200`, o proxy está funcionando.

### 3. Testar integração de SSO (obter token no Keycloak)

Credenciais de desenvolvimento (definidas no realm import):

- `client_id`: `inventory-adapter-client`
- `client_secret`: `inventory-adapter-secret`
- `username`: `inventory-svc`
- `password`: `inventory-svc-pass`

Gerar token:

```bash
curl -s -X POST 'http://localhost:8081/realms/inventory-dev/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=inventory-adapter-client' \
  -d 'client_secret=inventory-adapter-secret' \
  -d 'username=inventory-svc' \
  -d 'password=inventory-svc-pass' | jq -r .access_token
```

### 4. Chamar endpoint protegido via Adapter

```bash
TOKEN=$(curl -s -X POST 'http://localhost:8081/realms/inventory-dev/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=inventory-adapter-client' \
  -d 'client_secret=inventory-adapter-secret' \
  -d 'username=inventory-svc' \
  -d 'password=inventory-svc-pass' | jq -r .access_token)

curl -i 'http://localhost:9090/realms/inventory-dev/protocol/openid-connect/userinfo' \
  -H "Authorization: Bearer ${TOKEN}"
```

### 5. Validar backend estático (benchmark)

O backend estático responde JSON fixo em qualquer path, ideal para medir o overhead puro do adapter.

```bash
curl -i http://localhost:9091/qualquer/path
```

Resultado esperado: `200` com JSON contendo `"source":"static-backend"`.

### 6. Ver traces no Zipkin

- Acesse: `http://localhost:9411`
- Procure pelo serviço `http` (nome do listener do adapter).
- Compare traces da porta `9090` (com auth) vs `9091` (sem auth, upstream estático) para isolar o overhead do adapter.

## Arquivos principais do ambiente local

- `docker-compose.yml`
- `compose/keycloak/realm-inventory-dev.json`
- `compose/static-backend/default.conf`
- `config/adapter.yaml`

