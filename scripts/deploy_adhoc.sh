#!/usr/bin/env bash
###############################################################################
# deploy_adhoc.sh — Build & deploy n-gate JAR para VMs Vagrant (ad-hoc)
#
# Uso:
#   ./scripts/deploy_adhoc.sh              # build + deploy em ngate-1 e ngate-2
#   ./scripts/deploy_adhoc.sh --skip-build  # pula o build, usa o último JAR
#   ./scripts/deploy_adhoc.sh ngate-1       # deploy apenas em ngate-1
#   ./scripts/deploy_adhoc.sh --skip-build ngate-2  # sem build, apenas ngate-2
###############################################################################
set -euo pipefail

# ─── Configuração ────────────────────────────────────────────────────────────
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VAGRANT_DIR="${PROJECT_ROOT}/n-gate-test-case"
JAR_SOURCE="${PROJECT_ROOT}/target/n-gate-1.0-SNAPSHOT.jar"
REMOTE_JAR="/opt/n-gate/n-gate.jar"
SERVICE_NAME="n-gate"
ALL_VMS=("ngate-1" "ngate-2")

# ─── Cores ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log_info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ─── Parse de argumentos ────────────────────────────────────────────────────
SKIP_BUILD=false
TARGET_VMS=()

for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=true ;;
    ngate-*)      TARGET_VMS+=("$arg") ;;
    *)            log_error "Argumento desconhecido: $arg"; exit 1 ;;
  esac
done

# Se nenhuma VM foi especificada, usa todas
if [[ ${#TARGET_VMS[@]} -eq 0 ]]; then
  TARGET_VMS=("${ALL_VMS[@]}")
fi

# ─── Valida diretório do Vagrant ─────────────────────────────────────────────
if [[ ! -f "${VAGRANT_DIR}/Vagrantfile" ]]; then
  log_error "Vagrantfile não encontrado em ${VAGRANT_DIR}"
  log_error "Certifique-se de que o submodule n-gate-test-case está inicializado."
  exit 1
fi

# ─── Step 1: Build do Frontend (n-gate-ui) ──────────────────────────────────
if [[ "$SKIP_BUILD" == true ]]; then
  log_warn "Build ignorado (--skip-build)"
else
  UI_DIR="${PROJECT_ROOT}/n-gate-ui"

  if [[ -d "$UI_DIR" ]]; then
    log_info "Buildando o frontend (n-gate-ui)..."
    cd "$UI_DIR"

    if [[ ! -d "node_modules" ]]; then
      log_info "Instalando dependências do frontend (npm install)..."
      npm install --silent
    fi

    npm run build
    log_ok "Frontend buildado → src/main/resources/static/dashboard"
  else
    log_warn "Diretório n-gate-ui não encontrado. Pulando build do frontend."
  fi

  # ─── Step 2: Build do JAR ─────────────────────────────────────────────────
  log_info "Buildando o JAR com Maven (mvn clean package -DskipTests)..."
  cd "$PROJECT_ROOT"
  mvn clean package -DskipTests -q
  log_ok "Build concluído com sucesso."
fi

# Valida existência do JAR
if [[ ! -f "$JAR_SOURCE" ]]; then
  log_error "JAR não encontrado: ${JAR_SOURCE}"
  log_error "Execute o build primeiro ou verifique o path."
  exit 1
fi

JAR_SIZE=$(du -h "$JAR_SOURCE" | cut -f1)
log_info "JAR: ${JAR_SOURCE} (${JAR_SIZE})"

# ─── Step 2: Deploy em cada VM ──────────────────────────────────────────────
deploy_to_vm() {
  local vm_name="$1"

  log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  log_info "Iniciando deploy em ${vm_name}..."

  # Verifica se a VM está rodando
  local vm_status
  vm_status=$(cd "$VAGRANT_DIR" && vagrant status "$vm_name" --machine-readable \
    | grep ",state," | cut -d',' -f4)

  if [[ "$vm_status" != "running" ]]; then
    log_error "VM ${vm_name} não está running (status: ${vm_status})"
    log_error "Execute 'cd n-gate-test-case && vagrant up ${vm_name}' primeiro."
    return 1
  fi

  # Upload do JAR via vagrant upload
  log_info "[${vm_name}] Fazendo upload do JAR..."
  (cd "$VAGRANT_DIR" && vagrant upload "$JAR_SOURCE" /tmp/n-gate-deploy.jar "$vm_name")
  log_ok "[${vm_name}] Upload concluído."

  # Parar serviço, copiar JAR, reiniciar serviço
  log_info "[${vm_name}] Parando serviço ${SERVICE_NAME}..."
  (cd "$VAGRANT_DIR" && vagrant ssh "$vm_name" -c \
    "sudo systemctl stop ${SERVICE_NAME} || true")

  log_info "[${vm_name}] Copiando JAR para ${REMOTE_JAR}..."
  (cd "$VAGRANT_DIR" && vagrant ssh "$vm_name" -c \
    "sudo cp /tmp/n-gate-deploy.jar ${REMOTE_JAR} && sudo chown n-gate:n-gate ${REMOTE_JAR} && rm -f /tmp/n-gate-deploy.jar")

  log_info "[${vm_name}] Reiniciando serviço ${SERVICE_NAME}..."
  (cd "$VAGRANT_DIR" && vagrant ssh "$vm_name" -c \
    "sudo systemctl start ${SERVICE_NAME}")

  # Aguarda um pouco e verifica status
  sleep 3

  log_info "[${vm_name}] Verificando status do serviço..."
  (cd "$VAGRANT_DIR" && vagrant ssh "$vm_name" -c \
    "sudo systemctl --no-pager --full status ${SERVICE_NAME}") || true

  log_ok "[${vm_name}] Deploy concluído!"
}

FAILED=0
for vm in "${TARGET_VMS[@]}"; do
  if ! deploy_to_vm "$vm"; then
    log_error "Deploy falhou para ${vm}"
    FAILED=$((FAILED + 1))
  fi
done

# ─── Resumo ─────────────────────────────────────────────────────────────────
echo ""
log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [[ $FAILED -eq 0 ]]; then
  log_ok "Deploy ad-hoc concluído com sucesso em: ${TARGET_VMS[*]}"
else
  log_error "Deploy falhou em ${FAILED} VM(s). Verifique os logs acima."
  exit 1
fi
