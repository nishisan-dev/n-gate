# Docker — Imagem de Produção

O ishin-gateway publica imagens Docker oficiais no DockerHub em cada release.

**Repositório:** [`lnishisan/ishin-gateway`](https://hub.docker.com/r/lnishisan/ishin-gateway)

---

## Tags Disponíveis

| Tag | Descrição |
|-----|-----------|
| `<version>` | Versão específica (ex: `1.2.0`) — imutável |
| `latest` | Última release publicada |

---

## Quick Start

```bash
docker run -d \
  --name ishin-gateway \
  -p 9091:9091 \
  -p 9190:9190 \
  -v $(pwd)/adapter.yaml:/app/config/adapter.yaml:ro \
  -v $(pwd)/rules:/app/rules:ro \
  lnishisan/ishin-gateway:latest
```

---

## Portas Expostas

| Porta | Serviço |
|-------|---------|
| `9091` | Listener HTTP (padrão do template de configuração — configurável via `adapter.yaml`) |
| `9190` | Management API (Actuator: health, prometheus, admin) |
| `7100` | NGrid mesh TCP (cluster mode) |
| `18080` | Porta interna Spring Boot |

> [!NOTE]
> As portas dos listeners são definidas no `adapter.yaml`, não na imagem. Os valores acima são os padrões dos exemplos de configuração. Publique apenas as portas que seu deploy utiliza.

---

## Variáveis de Ambiente

| Variável | Default | Descrição |
|----------|---------|-----------|
| `ISHIN_CONFIG` | `config/adapter.yaml` | Caminho do arquivo de configuração (relativo ao `/app`) |
| `ZIPKIN_ENDPOINT` | — | URL do Zipkin collector (ex: `http://zipkin:9411/api/v2/spans`) |
| `TRACING_ENABLED` | `true` | Habilita/desabilita tracing distribuído |
| `TRACING_SAMPLE_RATE` | `1.0` | Taxa de amostragem (`0.0` a `1.0`) |
| `ISHIN_CLUSTER_NODE_ID` | hostname | ID do nó no cluster NGrid |
| `ISHIN_INSTANCE_ID` | hostname | ID da instância para tracing spans |
| `MANAGEMENT_PORT` | `9190` | Porta do Actuator (health, prometheus, admin API) |
| `SPRING_PROFILES_DEFAULT` | `dev` | Profile Spring Boot ativo |
| `JAVA_OPTS` | — | Opções JVM adicionais (sobrescreve flags do ENTRYPOINT se usado via wrapper) |

---

## Volumes e Bind Mounts

| Container Path | Tipo | Descrição |
|----------------|------|-----------|
| `/app/config/adapter.yaml` | **Obrigatório** | Arquivo de configuração principal |
| `/app/rules/` | **Obrigatório** | Diretório com scripts Groovy de regras |
| `/app/ssl/` | Opcional | Keystores Java para listeners HTTPS |
| `/app/data/` | Opcional | Dados persistentes (NGrid, dashboard H2) |

---

## Exemplo — Docker Compose (standalone)

```yaml
services:
  ishin-gateway:
    image: lnishisan/ishin-gateway:latest
    ports:
      - "9091:9091"
      - "9190:9190"
    environment:
      ISHIN_CONFIG: config/adapter.yaml
      TRACING_ENABLED: "false"
    volumes:
      - ./config/adapter.yaml:/app/config/adapter.yaml:ro
      - ./rules:/app/rules:ro
    restart: unless-stopped
```

## Exemplo — Docker Compose (cluster 3 nós)

```yaml
services:
  ishin-1:
    image: lnishisan/ishin-gateway:1.2.0
    hostname: ishin-1
    ports:
      - "9091:9091"
      - "9190:9190"
    environment:
      ISHIN_CONFIG: config/adapter.yaml
      ISHIN_CLUSTER_NODE_ID: ishin-1
      ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans
    volumes:
      - ./config/adapter-cluster.yaml:/app/config/adapter.yaml:ro
      - ./rules:/app/rules:ro
      - ishin-1-data:/app/data

  ishin-2:
    image: lnishisan/ishin-gateway:1.2.0
    hostname: ishin-2
    ports:
      - "9092:9091"
      - "9191:9190"
    environment:
      ISHIN_CONFIG: config/adapter.yaml
      ISHIN_CLUSTER_NODE_ID: ishin-2
      ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans
    volumes:
      - ./config/adapter-cluster.yaml:/app/config/adapter.yaml:ro
      - ./rules:/app/rules:ro
      - ishin-2-data:/app/data

  ishin-3:
    image: lnishisan/ishin-gateway:1.2.0
    hostname: ishin-3
    ports:
      - "9093:9091"
      - "9192:9190"
    environment:
      ISHIN_CONFIG: config/adapter.yaml
      ISHIN_CLUSTER_NODE_ID: ishin-3
      ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans
    volumes:
      - ./config/adapter-cluster.yaml:/app/config/adapter.yaml:ro
      - ./rules:/app/rules:ro
      - ishin-3-data:/app/data

volumes:
  ishin-1-data:
  ishin-2-data:
  ishin-3-data:
```

---

## Detalhes da Imagem

| Propriedade | Valor |
|-------------|-------|
| **Base image** | `eclipse-temurin:21-jre` |
| **JVM** | OpenJDK 21 (Temurin) |
| **GC** | ZGC Generational (`-XX:+UseZGC -XX:+ZGenerational`) |
| **Memória padrão** | `-Xms128m -Xmx256m` |
| **Workdir** | `/app` |
| **Entrypoint** | `java -jar app.jar` |

> [!TIP]
> Para aumentar a memória em ambientes de produção, sobrescreva o entrypoint ou use variável `JAVA_TOOL_OPTIONS`:
> ```bash
> docker run -e JAVA_TOOL_OPTIONS="-Xms512m -Xmx1g" lnishisan/ishin-gateway:latest
> ```

---

## Health Check

O endpoint de health está disponível na porta de management:

```bash
curl http://localhost:9190/actuator/health
```

Exemplo de health check no Docker Compose:

```yaml
healthcheck:
  test: ["CMD", "wget", "-qO-", "http://127.0.0.1:9190/actuator/health"]
  interval: 10s
  timeout: 3s
  retries: 3
  start_period: 15s
```
