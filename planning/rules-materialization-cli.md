# Materialização de Rules no rulesBasePath + CLI Script

Quando um bundle de rules é deployado via Admin API (ou replicado via NGrid), os scripts são materializados num diretório temporário (`/tmp/ngate-rules-vN-xxx`) ao invés do `rulesBasePath` configurado (ex: `/etc/n-gate/rules`). Isso causa confusão operacional pois um `ls /etc/n-gate/rules` não mostra as regras ativas. Além disso, o systemd unit impede escrita em `/etc/n-gate` e não existe um CLI amigável para deploy.

## Proposed Changes

### Componente 1: RulesBundleManager — Materializar no rulesBasePath

#### [MODIFY] [RulesBundleManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/rules/RulesBundleManager.java)

Alterar `applyBundleLocally()` para:
1. Resolver o `rulesBasePath` do primeiro endpoint configurado via `ConfigurationManager`.
2. **Limpar** o diretório `rulesBasePath` existente (delete recursivo dos arquivos antigos).
3. **Materializar** os scripts do bundle diretamente no `rulesBasePath`.
4. Usar o `rulesBasePath` como root do `GroovyScriptEngine`, eliminando o tempDir.

Isso garante que o filesystem reflete o estado runtime e sobrevive restarts sem depender do `rules-bundle.dat`.

---

### Componente 2: AdminController — Endpoint de listagem

#### [MODIFY] [AdminController.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/admin/AdminController.java)

Adicionar endpoint `GET /admin/rules/list` que retorna:
```json
{
  "status": "active",
  "version": 3,
  "deployedAt": "2026-03-11T09:40:00Z",
  "scripts": [
    {"name": "default/Rules.groovy", "sizeBytes": 512}
  ]
}
```

Protegido pela mesma autenticação `X-API-Key` existente.

---

### Componente 3: Debian Packaging — Permissões e CLI

#### [MODIFY] [n-gate.service](file:///home/lucas/Projects/n-gate/debian/n-gate.service)

Alterar linha `ReadOnlyPaths=/etc/n-gate /opt/n-gate` para separar:
- `ReadOnlyPaths=/opt/n-gate` (somente o JAR)
- `ReadWritePaths=/etc/n-gate/rules` (permitir escrita ao materializar bundles)
- `/etc/n-gate` em si continua restrito (adapter.yaml, ssl, log4j2.xml protegidos)

#### [MODIFY] [postinst](file:///home/lucas/Projects/n-gate/debian/postinst)

Adicionar criação e permissões do diretório `/etc/n-gate/rules`:
```bash
mkdir -p /etc/n-gate/rules
chown -R n-gate:n-gate /etc/n-gate/rules
```

#### [NEW] [ngate-cli](file:///home/lucas/Projects/n-gate/debian/ngate-cli)

Script Bash CLI com subcomandos:
- `ngate-cli deploy <diretório-de-rules>` — empacota os `.groovy` e faz POST multipart para `/admin/rules/deploy`
- `ngate-cli list` — faz GET em `/admin/rules/list` e exibe a lista de scripts
- `ngate-cli version` — faz GET em `/admin/rules/version` e exibe a versão ativa

Configuração via variáveis de ambiente ou arquivo `/etc/n-gate/cli.conf`:
- `NGATE_ADMIN_URL` (default: `http://localhost:9190`)
- `NGATE_API_KEY`

Dependência: `curl` e `jq` (já comuns em servidores Linux).

#### [MODIFY] [release.yml](file:///home/lucas/Projects/n-gate/.github/workflows/release.yml)

Adicionar no step "Build .deb package":
```bash
mkdir -p "${PKG}/usr/bin"
cp debian/ngate-cli "${PKG}/usr/bin/ngate-cli"
chmod 755 "${PKG}/usr/bin/ngate-cli"
```

#### [MODIFY] [control](file:///home/lucas/Projects/n-gate/debian/control)

Adicionar `curl, jq` ao campo `Depends` para garantir que o CLI funcione.

---

## Verification Plan

### Automated Tests

1. **Build Maven:**
   ```bash
   cd /home/lucas/Projects/n-gate && mvn clean compile -DskipTests
   ```

2. **Teste de integração existente** (`NGridClusterRulesDeployIntegrationTest`):
   - Valida deploy standalone (T8), replicação cluster (T9), auth (T10), e payload vazio (T11).
   - O teste já faz deploy e verifica versão nos dois nós. Após nossas mudanças, o comportamento externo não muda — apenas o local de materialização.
   - Comando: `cd /home/lucas/Projects/n-gate && mvn test -pl . -Dtest=NGridClusterRulesDeployIntegrationTest` (requer Docker rodando para Testcontainers)

> [!IMPORTANT]
> O teste de integração com Testcontainers requer Docker e demora ~2-3 minutos. Para a etapa de verificação, rodaremos inicialmente apenas o `mvn clean compile` para garantir que não há erros de compilação. O teste de integração completo ficará a critério do usuário, pois depende de Docker + tempo.

### Manual Verification

O script `ngate-cli` poderá ser testado manualmente contra uma instância local:
```bash
# Testar help
./debian/ngate-cli --help

# Testar listagem (requer instância rodando)
NGATE_API_KEY=test-key NGATE_ADMIN_URL=http://localhost:9190 ./debian/ngate-cli list

# Testar deploy
NGATE_API_KEY=test-key NGATE_ADMIN_URL=http://localhost:9190 ./debian/ngate-cli deploy rules/
```
