#!/bin/bash
set -euo pipefail

# Redespliega el MCP Server en el droplet de Digital Ocean por SSH: hace pull de la
# imagen mas reciente (Docker Hub u otro registry) y reinicia solo el servicio
# mcp-server -- Keycloak/OpenBao/Caddy siguen corriendo sin interrupcion.
#
# Requiere:
# - Haber buildeado y pusheado la imagen antes (ver README.md, seccion "Build y push"):
#     docker buildx build --platform linux/amd64 -t <tu-registry>/jde-mcp-server:prod --push ..
# - docker/deploy.env con DO_HOST / DO_USERNAME / DO_SSH_KEY_PATH / REMOTE_DIR
#   (cp deploy.env.example deploy.env y completar).
# - El droplet ya tiene el stack levantado (docker compose --profile prod --env-file
#   .env.prod up -d) al menos una vez -- ver README.md, "Primera vez en el droplet".
#
# Uso:
#   ./deploy.sh                # profile=prod, env-file=.env.prod
#   ./deploy.sh stage .env.stage

PROFILE="${1:-prod}"
ENV_FILE="${2:-.env.${PROFILE}}"
SERVICE="mcp-server"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(dirname "$SCRIPT_DIR")"

if [[ -f "${DOCKER_DIR}/deploy.env" ]]; then
  source "${DOCKER_DIR}/deploy.env"
fi

DO_HOST="${DO_HOST:?'DO_HOST requerido -- completar docker/deploy.env (ver deploy.env.example)'}"
DO_USERNAME="${DO_USERNAME:-root}"
REMOTE_DIR="${REMOTE_DIR:?'REMOTE_DIR requerido -- completar docker/deploy.env'}"

SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10"
[[ -n "${DO_SSH_KEY_PATH:-}" ]] && SSH_OPTS="${SSH_OPTS} -i ${DO_SSH_KEY_PATH}"

echo "==> Redesplegando '$SERVICE' en ${DO_USERNAME}@${DO_HOST}:${REMOTE_DIR} (profile=${PROFILE}, env-file=${ENV_FILE})"

# shellcheck disable=SC2087
ssh ${SSH_OPTS} "${DO_USERNAME}@${DO_HOST}" bash -s <<REMOTE_SCRIPT
set -euo pipefail
cd "${REMOTE_DIR}"
docker compose --profile ${PROFILE} --env-file ${ENV_FILE} pull ${SERVICE}
docker compose --profile ${PROFILE} --env-file ${ENV_FILE} up -d ${SERVICE}
docker compose --profile ${PROFILE} --env-file ${ENV_FILE} ps ${SERVICE}
REMOTE_SCRIPT

echo "==> Listo. Ver logs con:"
echo "    ssh ${SSH_OPTS} ${DO_USERNAME}@${DO_HOST} \"cd ${REMOTE_DIR} && docker compose --profile ${PROFILE} --env-file ${ENV_FILE} logs -f ${SERVICE}\""
