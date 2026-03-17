# Publicar Imagem Docker no DockerHub via GitHub Actions

Adicionar um job ao pipeline de release para construir e publicar a imagem Docker do ishin-gateway no DockerHub (`lnishisan/ishin-gateway`) com a tag da versão da release.

## Contexto

- **Workflow atual:** `release.yml` — trigger em `release: published`. Já faz build Maven com a versão da tag, roda testes, gera `.deb` e faz upload na release.
- **Dockerfile existente:** Multi-stage (Maven builder → JRE 21 runtime). Problemas:
  - JAR hardcoded: `ishin-gateway-1.0-SNAPSHOT.jar`
  - Depende de `settings.xml` local (não commitado)
- **Secrets já criados:** `DOCKER_HUB_USER` e `DOCKER_HUB_TOKEN`

## Decisão de Design

> [!IMPORTANT]
> **Não usar o Dockerfile para build dentro do CI.** O pipeline já faz build Maven + frontend de forma controlada. O approach mais limpo é:
> 1. Reutilizar o JAR já construído pelo job `build-deb`
> 2. Usar um **Dockerfile simplificado no CI** que apenas copia o JAR pré-construído
> 3. Manter o `Dockerfile` existente na raiz intocado (ele serve para builds locais)

Essa abordagem evita duplicar o build Maven dentro do Docker e elimina o problema do `settings.xml`.

## Proposed Changes

### Pipeline CI/CD

#### [MODIFY] [release.yml](file:///home/lucas/Projects/ishin-gateway/.github/workflows/release.yml)

Adicionar um novo job `docker-publish` que depende do job `build-deb`:

1. **Upload artifact:** No job `build-deb`, adicionar um step para salvar o JAR como GitHub Actions artifact
2. **Novo job `docker-publish`:**
   - `needs: build-deb` — espera o build Maven terminar
   - Download do artifact (JAR)
   - Login no DockerHub via `docker/login-action`
   - Setup do Docker Buildx via `docker/setup-buildx-action`
   - Build & Push via `docker/build-push-action` com context inline
   - Tags: `lnishisan/ishin-gateway:<version>` + `lnishisan/ishin-gateway:latest`

---

### Dockerfile para CI

#### [NEW] [Dockerfile.ci](file:///home/lucas/Projects/ishin-gateway/Dockerfile.ci)

Dockerfile simplificado que recebe o JAR pré-construído:

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
ARG JAR_FILE=ishin-gateway.jar
COPY ${JAR_FILE} app.jar
EXPOSE 9091 9190 7100 18080
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Xms128m", "-Xmx256m", "-jar", "app.jar"]
```

---

### Dockerfile Local (manutenção)

#### [MODIFY] [Dockerfile](file:///home/lucas/Projects/ishin-gateway/Dockerfile)

Corrigir o nome hardcoded do JAR para usar wildcard/glob no COPY (boa prática):

```diff
-COPY --from=builder /build/target/ishin-gateway-1.0-SNAPSHOT.jar app.jar
+COPY --from=builder /build/target/ishin-gateway-*.jar app.jar
```

## Verification Plan

### Manual Verification

1. **Commit e criar uma release** no GitHub para disparar o pipeline
2. **Verificar no GitHub Actions** que o job `docker-publish` rodou com sucesso
3. **Verificar no DockerHub** que a imagem `lnishisan/ishin-gateway` foi publicada com a tag da versão e `latest`
