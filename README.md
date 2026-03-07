# Inventory Adapter - Dev Local

Este projeto está preparado para rodar localmente com um único `docker compose`, subindo:

- `inventory-adapter`
- `keycloak` (SSO)
- `zipkin` (tracing)

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

## Portas dos microserviços

- `inventory-adapter`: `9090`
- `inventory-adapter` (Spring/diagnóstico): `18080`
- `keycloak`: `8081` (mapeado para `8080` interno)
- `zipkin`: `9411`

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

### 5. Ver traces no Zipkin

- Acesse: `http://localhost:9411`
- Procure pelo serviço `http` (nome do listener do adapter).

## Arquivos principais do ambiente local

- `docker-compose.yml`
- `compose/keycloak/realm-inventory-dev.json`
- `config/adapter.yaml`

