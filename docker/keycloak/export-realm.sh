#!/bin/bash
set -euo pipefail

# Exporta el realm de Keycloak (jde-integration) a JSON, para poder importarlo
# automaticamente al levantar un Keycloak nuevo (ver "command: start-dev --import-realm"
# en docker-compose.yml, que monta docker/keycloak/realm-export.json) en vez de tener
# que reconfigurar el realm/client a mano en la consola admin cada vez.
#
# Requiere que el stack Keycloak + Postgres ya este corriendo (docker compose up).
# Keycloak Quarkus no exporta de forma confiable mientras el mismo server sirve
# trafico contra la misma base -- este script para brevemente el contenedor de
# Keycloak, corre un contenedor temporal conectado a la MISMA base Postgres para
# hacer el export, y reinicia el contenedor original al terminar.
#
# Exporta tambien los usuarios del realm (--users realm_file), incluyendo email y
# password hasheada (bcrypt/pbkdf2, no en texto plano) -- por decision explicita, a
# pesar de que esto mete datos personales reales en un archivo committeado al repo.
# Si en algun momento el repo se comparte mas ampliamente o se hace publico, reevaluar
# esto (se puede volver a excluir con --users skip).
#
# Uso:
#   ./export-realm.sh
#   KC_REALM=otro-realm KEYCLOAK_CONTAINER=otro-nombre ./export-realm.sh

REALM="${KC_REALM:-jde-integration}"
KEYCLOAK_CONTAINER="${KEYCLOAK_CONTAINER:-keycloak}"
DB_CONTAINER="${DB_CONTAINER:-keycloak-db}"
DB_PASSWORD="${KC_DB_PASSWORD:-changeme_db_password}"
IMAGE="quay.io/keycloak/keycloak:26.0"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_FILE="$SCRIPT_DIR/realm-export.json"
TMP_DIR="$SCRIPT_DIR/.export-tmp"

if ! docker inspect "$DB_CONTAINER" >/dev/null 2>&1; then
  echo "No se encontro el contenedor '$DB_CONTAINER' corriendo. Levantar el stack primero (docker compose up -d)." >&2
  exit 1
fi

NETWORK=$(docker inspect "$DB_CONTAINER" --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}')
if [[ -z "$NETWORK" ]]; then
  echo "No se pudo determinar la red de '$DB_CONTAINER'." >&2
  exit 1
fi

echo "==> Deteniendo $KEYCLOAK_CONTAINER (se reinicia al terminar el export)..."
docker stop "$KEYCLOAK_CONTAINER"

echo "==> Exportando realm '$REALM' via contenedor temporal (misma base Postgres: $DB_CONTAINER, red: $NETWORK)..."
mkdir -p "$TMP_DIR"
docker run --rm \
  --network "$NETWORK" \
  -e KC_DB=postgres \
  -e KC_DB_URL="jdbc:postgresql://${DB_CONTAINER}:5432/keycloak" \
  -e KC_DB_USERNAME=keycloak \
  -e KC_DB_PASSWORD="$DB_PASSWORD" \
  -v "$TMP_DIR":/tmp/kc-export \
  "$IMAGE" \
  export --dir /tmp/kc-export --realm "$REALM" --users realm_file

echo "==> Reiniciando $KEYCLOAK_CONTAINER..."
docker start "$KEYCLOAK_CONTAINER"

EXPORTED="$TMP_DIR/${REALM}-realm.json"
if [[ -f "$EXPORTED" ]]; then
  mv "$EXPORTED" "$OUTPUT_FILE"
  rm -rf "$TMP_DIR"
  echo "==> Listo: $OUTPUT_FILE"
  echo "    Revisar el diff antes de commitear: git diff -- docker/keycloak/realm-export.json"
else
  echo "No se genero el archivo esperado ($EXPORTED). Revisar el log de arriba." >&2
  exit 1
fi
