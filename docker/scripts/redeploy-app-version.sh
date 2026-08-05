#!/bin/bash
set -euo pipefail

# Despliega una nueva version de la APP (jar publicado en JFrog) sin tocar la
# imagen Docker: actualiza APP_VERSION en el .env remoto y fuerza la recreacion
# del contenedor mcp-server (que dispara docker/entrypoint.sh, descargando esa
# version desde JFrog). No hace "docker compose pull" ni requiere una imagen
# nueva -- ese es justamente el punto (ver docker/README.md).
#
# Requiere:
# - Haber publicado la version en JFrog antes: mvn clean deploy (ver pom.xml,
#   distributionManagement) -- con el <version> del pom.xml en la version que
#   se quiere desplegar.
# - docker/deploy.env con DO_HOST / DO_USERNAME / DO_SSH_KEY_PATH / REMOTE_DIR
#   (mismo archivo que usa deploy.sh).
# - El droplet ya tiene el stack levantado con APP_VERSION/JFROG_* completos en
#   su .env.{profile} (ver .env.stage.example / .env.prod.example).
#
# Uso:
#   ./redeploy-app-version.sh 1.2.3                # profile=prod, env-file=.env.prod
#   ./redeploy-app-version.sh 1.2.3 stage .env.stage
#   ./redeploy-app-version.sh latest               # usa la ultima version publicada

VERSION="${1:?Uso: ./redeploy-app-version.sh <version|latest> [profile] [env-file]}"
PROFILE="${2:-prod}"
ENV_FILE="${3:-.env.${PROFILE}}"
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

echo "==> Desplegando ${SERVICE} version '${VERSION}' en ${DO_USERNAME}@${DO_HOST}:${REMOTE_DIR} (profile=${PROFILE}, env-file=${ENV_FILE})"

# shellcheck disable=SC2087
ssh ${SSH_OPTS} "${DO_USERNAME}@${DO_HOST}" bash -s <<REMOTE_SCRIPT
set -euo pipefail
cd "${REMOTE_DIR}"

if grep -q '^APP_VERSION=' "${ENV_FILE}"; then
  sed -i "s|^APP_VERSION=.*|APP_VERSION=${VERSION}|" "${ENV_FILE}"
else
  echo "APP_VERSION=${VERSION}" >> "${ENV_FILE}"
fi

docker compose --profile ${PROFILE} --env-file ${ENV_FILE} up -d --force-recreate ${SERVICE}
docker compose --profile ${PROFILE} --env-file ${ENV_FILE} ps ${SERVICE}
REMOTE_SCRIPT

echo "==> Listo. Ver logs (confirmar que descargo la version correcta) con:"
echo "    ssh ${SSH_OPTS} ${DO_USERNAME}@${DO_HOST} \"cd ${REMOTE_DIR} && docker compose --profile ${PROFILE} --env-file ${ENV_FILE} logs -f ${SERVICE}\""
