#!/bin/bash
set -euo pipefail

# Arma un ZIP portable con SOLO lo necesario para correr el stack en otra PC:
# sin codigo fuente Java/Maven, sin secretos reales. La imagen del MCP Server
# se descarga de un registry (ver DEPLOYMENT.md, "Parte 3: Empaquetar y llevar
# a otra PC") -- este script no buildea ni pushea la imagen, solo empaqueta
# los archivos de configuracion.
#
# Uso:
#   ./package.sh [nombre-salida.zip]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(dirname "$SCRIPT_DIR")"
REPO_DIR="$(dirname "$DOCKER_DIR")"

OUTPUT_NAME="${1:-jde-mcp-server-docker-$(date +%Y%m%d).zip}"
BUNDLE_NAME="jde-mcp-server-docker"

WORK_DIR="$(mktemp -d)"
STAGE_DIR="$WORK_DIR/$BUNDLE_NAME"
mkdir -p "$STAGE_DIR"

echo "==> Copiando archivos (solo config/templates, sin secretos reales)..."

# Config del compose -- .env es de dev (placeholders, safe). Los .example son
# plantillas. Nunca se incluyen .env.stage / .env.prod / deploy.env reales
# (gitignored, con secretos) -- la otra PC crea los suyos desde los .example.
cp "$DOCKER_DIR/docker-compose.yml" "$STAGE_DIR/"
cp "$DOCKER_DIR/.env" "$STAGE_DIR/"
cp "$DOCKER_DIR/.env.stage.example" "$STAGE_DIR/"
cp "$DOCKER_DIR/.env.prod.example" "$STAGE_DIR/"
cp "$DOCKER_DIR/deploy.env.example" "$STAGE_DIR/"
cp "$DOCKER_DIR/README.md" "$STAGE_DIR/"

cp -r "$DOCKER_DIR/caddy" "$STAGE_DIR/"
cp -r "$DOCKER_DIR/keycloak" "$STAGE_DIR/"
rm -rf "$STAGE_DIR/keycloak/.export-tmp"
cp -r "$DOCKER_DIR/scripts" "$STAGE_DIR/"

cp "$REPO_DIR/Dockerfile" "$STAGE_DIR/"
cp "$REPO_DIR/DEPLOYMENT.md" "$STAGE_DIR/"

find "$STAGE_DIR" -name ".DS_Store" -delete

echo "==> Comprimiendo..."
OUTPUT_PATH="$REPO_DIR/$OUTPUT_NAME"
(cd "$WORK_DIR" && zip -rq "$OUTPUT_PATH" "$BUNDLE_NAME")

rm -rf "$WORK_DIR"

echo "==> Listo: $OUTPUT_PATH"
echo ""
echo "Contenido: docker-compose.yml, .env (dev), .env.stage/.env.prod.example,"
echo "deploy.env.example, caddy/, keycloak/, scripts/, Dockerfile, DEPLOYMENT.md."
echo ""
echo "En la otra PC: descomprimir, completar MCP_IMAGE en .env con la imagen"
echo "que pusheaste a un registry, y seguir DEPLOYMENT.md -> 'Parte 3'."
