#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="/Users/franciscogodinoconte/_IA/SpringIAProjects/JDEAtinaMCPPurchaseServer"
PROPERTIES_FILE="$PROJECT_DIR/src/main/resources/application.properties"
DEV_PROPERTIES_FILE="$PROJECT_DIR/src/main/resources/application-dev.properties"
LOCAL_PROPERTIES_FILE="$PROJECT_DIR/src/main/resources/application-local.properties"
DOCKER_DIR="$PROJECT_DIR/docker"

DEPLOY_MODE="${1:-local}"

MCP_IMAGE_NAME="92455890/jde-mcp-server"
MCP_IMAGE_VERSION="1.0.0"
MCP_IMAGE="${MCP_IMAGE_NAME}:${MCP_IMAGE_VERSION}"

usage() {
    echo "Uso:"
    echo "  $0 [local|server]"
    echo
    echo "Modos:"
    echo "  local   Configura el puerto 8080, compila, construye e inicia"
    echo "          el contenedor localmente."
    echo
    echo "  server  Configura el puerto 8070, compila, construye e inicia"
    echo "          el contenedor y publica la imagen Docker"
    echo "          usando multiarch-builder."
}

case "$DEPLOY_MODE" in
    local)
        SERVER_PORT="8070"
        PORT_PROPERTIES_FILE="$LOCAL_PROPERTIES_FILE"
        SPRING_PROFILE="local"
        ;;
    server)
        SERVER_PORT="8070"
        PORT_PROPERTIES_FILE="$DEV_PROPERTIES_FILE"
        SPRING_PROFILE="dev"
        ;;
    -h|--help)
        usage
        exit 0
        ;;
    *)
        echo "ERROR: Modo no válido: $DEPLOY_MODE"
        echo
        usage
        exit 1
        ;;
esac

echo "========================================"
echo "JDE Atina MCP Purchase Server deployment"
echo "========================================"
echo "Modo:   $DEPLOY_MODE"
echo "Puerto: $SERVER_PORT"
echo "Profile: $SPRING_PROFILE"

cd "$PROJECT_DIR"

if [[ ! -f "$PROPERTIES_FILE" ]]; then
    echo "ERROR: No se encontró el archivo:"
    echo "  $PROPERTIES_FILE"
    exit 1
fi

if [[ ! -f "$PORT_PROPERTIES_FILE" ]]; then
    echo "ERROR: No se encontró el archivo del profile $SPRING_PROFILE:"
    echo "  $PORT_PROPERTIES_FILE"
    exit 1
fi

echo
echo "1. Actualizando configuración..."

#
# Puerto según el profile utilizado:
#
#   local  -> application-local.properties -> 8080
#   server -> application-dev.properties   -> 8070
#
if ! grep -qE '^[[:space:]]*server\.port=' "$PORT_PROPERTIES_FILE"; then
    echo "ERROR: No se encontró la propiedad server.port en:"
    echo "  $PORT_PROPERTIES_FILE"
    exit 1
fi

sed -i '' -E \
    "s/^[[:space:]]*server\.port=.*/server.port=${SERVER_PORT}/" \
    "$PORT_PROPERTIES_FILE"

echo "Profile configurado: $SPRING_PROFILE"
echo "Archivo actualizado:  $PORT_PROPERTIES_FILE"
echo "Puerto configurado:"
grep -E '^[[:space:]]*server\.port=' "$PORT_PROPERTIES_FILE"

#
# Incremento de la versión interna del MCP Server.
#
# Ejemplo:
#   1.0.1-01 -> 1.0.1-02
#
VERSION_LINE="$(
    grep -E '^[[:space:]]*mcp\.ai\.mcp\.server\.version=' "$PROPERTIES_FILE" |
    head -n 1
)"

if [[ -z "$VERSION_LINE" ]]; then
    echo "ERROR: No se encontró la propiedad:"
    echo "  mcp.ai.mcp.server.version"
    exit 1
fi

CURRENT_VERSION="$(
    printf '%s\n' "$VERSION_LINE" |
    sed -E 's/^[[:space:]]*mcp\.ai\.mcp\.server\.version=[[:space:]]*//'
)"

if [[ ! "$CURRENT_VERSION" =~ ^(.+)-([0-9]+)$ ]]; then
    echo "ERROR: La versión no tiene el formato esperado:"
    echo "  $CURRENT_VERSION"
    echo
    echo "Formato esperado, por ejemplo:"
    echo "  1.0.1-01"
    exit 1
fi

VERSION_PREFIX="${BASH_REMATCH[1]}"
VERSION_SUFFIX="${BASH_REMATCH[2]}"
SUFFIX_LENGTH="${#VERSION_SUFFIX}"

NEXT_SUFFIX=$((10#$VERSION_SUFFIX + 1))

printf -v FORMATTED_SUFFIX \
    "%0${SUFFIX_LENGTH}d" \
    "$NEXT_SUFFIX"

NEW_VERSION="${VERSION_PREFIX}-${FORMATTED_SUFFIX}"

sed -i '' -E \
    "s|^[[:space:]]*mcp\.ai\.mcp\.server\.version=.*|mcp.ai.mcp.server.version=${NEW_VERSION}|" \
    "$PROPERTIES_FILE"

echo "Versión anterior: $CURRENT_VERSION"
echo "Versión nueva:    $NEW_VERSION"

echo
echo "2. Ejecutando Maven..."

mvn clean install

echo
echo "3. Ingresando al directorio Docker..."

if [[ ! -d "$DOCKER_DIR" ]]; then
    echo "ERROR: No se encontró el directorio:"
    echo "  $DOCKER_DIR"
    exit 1
fi

cd "$DOCKER_DIR"

if [[ ! -f "docker-compose.yml" &&
      ! -f "docker-compose.yaml" &&
      ! -f "compose.yml" &&
      ! -f "compose.yaml" ]]; then
    echo "ERROR: No se encontró un archivo Docker Compose en:"
    echo "  $DOCKER_DIR"
    exit 1
fi

echo
echo "4. Construyendo e iniciando el contenedor..."
echo "Puerto de la aplicación: $SERVER_PORT"

MCP_IMAGE="$MCP_IMAGE" \
MCP_SERVER_PORT="$SERVER_PORT" \
SPRING_PROFILES_ACTIVE="$SPRING_PROFILE" \
docker compose \
    --profile dev \
    up \
    -d \
    --build

echo
echo "5. Estado de los contenedores..."

MCP_IMAGE="$MCP_IMAGE" \
MCP_SERVER_PORT="$SERVER_PORT" \
SPRING_PROFILES_ACTIVE="$SPRING_PROFILE" \
docker compose \
    --profile dev \
    ps

#
# En modo server se publica la imagen multiarquitectura.
#
if [[ "$DEPLOY_MODE" == "server" ]]; then
    echo
    echo "6. Construyendo y publicando la imagen multiarquitectura..."
    echo "Imagen:  $MCP_IMAGE"
    echo "Builder: multiarch-builder"
    echo "Puerto:  $SERVER_PORT"
    echo

    MCP_IMAGE="$MCP_IMAGE" \
    MCP_SERVER_PORT="$SERVER_PORT" \
    SPRING_PROFILES_ACTIVE="$SPRING_PROFILE" \
    docker compose \
        --profile dev \
        build \
        --builder multiarch-builder \
        --push

    echo
    echo "Imagen publicada correctamente:"
    echo "  $MCP_IMAGE"
fi

echo
echo "========================================"
echo "Proceso completado correctamente"
echo "Modo:    $DEPLOY_MODE"
echo "Versión: $NEW_VERSION"
echo "Puerto:  $SERVER_PORT"
echo "Profile: $SPRING_PROFILE"

if [[ "$DEPLOY_MODE" == "server" ]]; then
    echo "Imagen:  $MCP_IMAGE"
fi

echo "========================================"