# Empacotamento .deb do n-gate com GitHub Actions

Empacotar o n-gate como `.deb` para distribuição Linux, com pipeline de CI/CD no GitHub Actions acionado por releases. O pacote segue o FHS e instala o gateway como um serviço systemd.

## Layout FHS do Pacote

| Path instalado | Conteúdo |
|---|---|
| `/opt/n-gate/n-gate.jar` | Fat JAR (Spring Boot) |
| `/etc/n-gate/adapter.yaml` | Configuração principal |
| `/etc/n-gate/rules/default/Rules.groovy` | Scripts de regras Groovy (exemplo) |
| `/etc/n-gate/ssl/` | Keystores (vazio — user popula) |
| `/var/log/n-gate/` | Logs da aplicação |
| `/lib/systemd/system/n-gate.service` | Unit file systemd |
| `/etc/logrotate.d/n-gate` | Rotação de logs |

---

## Proposed Changes

### Git Remote

#### [NEW] Remote GitHub
- Adicionar remote `origin` → `git@github.com:nishisan-dev/n-gate.git`
- Push da branch `main`

---

### Packaging Assets

#### [NEW] [debian/](file:///home/lucas/Projects/n-gate/debian/)

Criar a estrutura Debian control dentro do projeto:

```
debian/
├── control              # Metadados do pacote (nome, versão, deps)
├── conffiles            # Lista de conffiles protegidos
├── postinst             # Script pós-instalação (cria user, dirs, enable service)
├── prerm                # Script pré-remoção (stop service)
├── postrm               # Script pós-remoção (cleanup)
├── n-gate.service       # Systemd unit file
├── n-gate.logrotate     # Logrotate config
└── rules/
    └── default/
        └── Rules.groovy # Exemplo de regra padrão
```

**`debian/control`** — Metadados principais:
```
Package: n-gate
Version: 1.0.0
Architecture: all
Maintainer: Lucas Nishimura <lucas.nishimura@gmail.com>
Depends: default-jre-headless (>= 21) | openjdk-21-jre-headless
Section: net
Priority: optional
Description: n-gate API Gateway
 High-performance reverse proxy and API gateway with
 Groovy-based dynamic routing rules and OAuth2 support.
```

**`debian/conffiles`**:
```
/etc/n-gate/adapter.yaml
/etc/n-gate/rules/default/Rules.groovy
```

**`debian/postinst`**:
```bash
#!/bin/bash
set -e
# Cria user de sistema
if ! id -u n-gate >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin n-gate
fi
# Cria dirs de runtime
mkdir -p /var/log/n-gate
mkdir -p /etc/n-gate/ssl
chown -R n-gate:n-gate /var/log/n-gate
chown -R n-gate:n-gate /etc/n-gate/ssl
# Habilita e inicia o serviço
systemctl daemon-reload
systemctl enable n-gate.service
```

**`debian/prerm`**:
```bash
#!/bin/bash
set -e
systemctl stop n-gate.service || true
systemctl disable n-gate.service || true
```

**`debian/n-gate.service`**:
```ini
[Unit]
Description=n-gate API Gateway
After=network.target

[Service]
Type=simple
User=n-gate
Group=n-gate
WorkingDirectory=/etc/n-gate
ExecStart=/usr/bin/java \
    -jar /opt/n-gate/n-gate.jar \
    -Dlog4j.configurationFile=/etc/n-gate/log4j2.xml
Restart=on-failure
RestartSec=10
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/log/n-gate
ReadOnlyPaths=/etc/n-gate /opt/n-gate

[Install]
WantedBy=multi-user.target
```

---

### GitHub Actions Pipeline

#### [NEW] [release.yml](file:///home/lucas/Projects/n-gate/.github/workflows/release.yml)

Pipeline acionado por `release: [published]`. Etapas:

1. **Build** — `mvn -DskipTests package` com JDK 21.
2. **Package .deb** — Monta a árvore de diretórios FHS e executa `dpkg-deb --build`.
3. **Upload Asset** — Anexa o `.deb` à release via `gh release upload`.

```yaml
name: Build and Package .deb
on:
  release:
    types: [published]
jobs:
  build-deb:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: maven
      - run: mvn -B -DskipTests package
      - name: Build .deb
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          PKG="n-gate_${VERSION}_all"
          # Montar árvore FHS
          mkdir -p "${PKG}/opt/n-gate"
          mkdir -p "${PKG}/etc/n-gate/rules/default"
          mkdir -p "${PKG}/etc/n-gate/ssl"
          mkdir -p "${PKG}/var/log/n-gate"
          mkdir -p "${PKG}/lib/systemd/system"
          mkdir -p "${PKG}/etc/logrotate.d"
          mkdir -p "${PKG}/DEBIAN"
          # Copiar artefatos
          cp target/n-gate-*.jar "${PKG}/opt/n-gate/n-gate.jar"
          cp config/adapter.yaml "${PKG}/etc/n-gate/"
          cp debian/rules/default/Rules.groovy "${PKG}/etc/n-gate/rules/default/"
          cp src/main/resources/log4j2.xml "${PKG}/etc/n-gate/"
          cp debian/n-gate.service "${PKG}/lib/systemd/system/"
          cp debian/n-gate.logrotate "${PKG}/etc/logrotate.d/n-gate"
          # Control files
          sed "s/^Version:.*/Version: ${VERSION}/" debian/control > "${PKG}/DEBIAN/control"
          cp debian/conffiles "${PKG}/DEBIAN/"
          cp debian/postinst "${PKG}/DEBIAN/" && chmod 755 "${PKG}/DEBIAN/postinst"
          cp debian/prerm "${PKG}/DEBIAN/" && chmod 755 "${PKG}/DEBIAN/prerm"
          if [ -f debian/postrm ]; then cp debian/postrm "${PKG}/DEBIAN/" && chmod 755 "${PKG}/DEBIAN/postrm"; fi
          # Build
          dpkg-deb --build "${PKG}"
      - name: Upload .deb to release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          gh release upload "${GITHUB_REF_NAME}" "n-gate_${VERSION}_all.deb"
```

---

### Ajuste no Código — Path Configurável das Rules via `adapter.yaml`

O path das rules será configurável diretamente no `adapter.yaml` via campo `rulesBasePath`, mantendo toda a configuração centralizada. Default: `"rules"` (compatível com dev).

#### [MODIFY] [EndPointConfiguration.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/configuration/EndPointConfiguration.java)

Adicionar campo `rulesBasePath` com getter/setter:

```diff
  private String ruleMapping;
  private Integer ruleMappingThreads = 1;
+ private String rulesBasePath = "rules";
```

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/n-gate/src/main/java/dev/nishisan/ngate/http/HttpProxyManager.java)

Alterar `initGse()` para ler o path da configuração:

```diff
 private void initGse() throws IOException {
-    this.gse = new GroovyScriptEngine("rules");
+    String rulesPath = configuration.getRulesBasePath();
+    this.gse = new GroovyScriptEngine(rulesPath);
     CompilerConfiguration config = this.gse.getConfig();
     config.setRecompileGroovySource(true);
     config.setMinimumRecompilationInterval(60);
-    logger.info("GroovyScriptEngine initialized with recompilation interval: 60s");
+    logger.info("GroovyScriptEngine initialized from [{}] with recompilation interval: 60s", rulesPath);
 }
```

No `adapter.yaml` de produção (empacotado no `.deb`):
```yaml
endpoints:
  default:
    rulesBasePath: "/etc/n-gate/rules"
```

---

## Verification Plan

### Automated Tests

1. **Build do pacote no CI:**
   ```bash
   # Local: simular o build do .deb
   mvn -DskipTests package
   # Montar árvore e verificar que dpkg-deb --build funciona
   ```

2. **Pipeline end-to-end:**
   - Criar release `v1.0.0` via `gh release create v1.0.0 --title "v1.0.0" --notes "Initial .deb release"`
   - Verificar no GitHub Actions que o job `build-deb` executa com sucesso
   - Verificar que o asset `n-gate_1.0.0_all.deb` aparece na release

### Manual Verification (Pós Pipeline)

1. Baixar o `.deb` gerado da release
2. Instalar com `sudo dpkg -i n-gate_*.deb`
3. Verificar que:
   - `/opt/n-gate/n-gate.jar` existe
   - `/etc/n-gate/adapter.yaml` existe
   - `/etc/n-gate/rules/default/Rules.groovy` existe
   - `/var/log/n-gate/` existe com owner `n-gate`
   - `systemctl status n-gate` mostra o serviço como `loaded`
