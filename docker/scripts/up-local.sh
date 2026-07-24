#!/bin/bash
set -euo pipefail

# Levanta el profile "local" (MCP Server containerizado, sin ngrok -- ver
# DEPLOYMENT.md, Parte 2b) siempre con el --env-file correcto. Existe porque
# "docker compose --profile local up" a secas cae en silencio a docker/.env
# (el de "dev", con la URL de ngrok) -- error facil de cometer y dificil de
# notar (el server arranca bien, solo el issuer queda mal).
#
# Uso:
#   ./up-local.sh              # todos los servicios del profile, con --build
#   ./up-local.sh mcp-server   # solo un servicio (pasa argumentos extra a "up")

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(dirname "$SCRIPT_DIR")"
cd "$DOCKER_DIR"

echo "==> docker compose --profile local --env-file .env.local up -d --build $*"
docker compose --profile local --env-file .env.local up -d --build "$@"

echo "==> Verificando que el issuer NO sea la URL de ngrok..."
sleep 3
METADATA="$(curl -s http://localhost:8080/.well-known/oauth-protected-resource || true)"
echo "$METADATA"
if echo "$METADATA" | grep -q "ngrok"; then
  echo "==> ⚠️  El issuer sigue apuntando a ngrok -- revisar docker/.env.local" >&2
  exit 1
fi
