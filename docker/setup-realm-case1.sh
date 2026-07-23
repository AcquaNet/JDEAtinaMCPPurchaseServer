#!/bin/bash
set -euo pipefail

# Este script configura el realm y el client de Keycloak para el Caso 1:
# Claude Desktop como cliente MCP, Authorization Code + PKCE.
#
# Requiere ejecutarse DENTRO del contenedor de keycloak, o tener kcadm.sh
# apuntando al server. Ejemplo de uso:
#   docker exec -it keycloak bash
#   /opt/keycloak/bin/kcadm.sh ...  (o copiar este script adentro y correrlo)

KC_URL="${KC_URL:-http://localhost:8080}"
ADMIN_USER="${KC_ADMIN_USER:-admin}"
ADMIN_PASS="${KC_ADMIN_PASSWORD:-changeme_admin_password}"
REALM="jde-integration"
CLIENT_ID="claude-desktop-mcp"

KCADM=/opt/keycloak/bin/kcadm.sh

echo "== Login como admin =="
$KCADM config credentials --server "$KC_URL" --realm master \
  --user "$ADMIN_USER" --password "$ADMIN_PASS"

echo "== Creando realm $REALM =="
$KCADM create realms -s realm="$REALM" -s enabled=true \
  -s sslRequired=external \
  -s registrationAllowed=false \
  -s accessTokenLifespan=300 \
  -s ssoSessionIdleTimeout=1800

echo "== Creando client publico para Claude Desktop (Auth Code + PKCE) =="
$KCADM create clients -r "$REALM" \
  -s clientId="$CLIENT_ID" \
  -s protocol=openid-connect \
  -s publicClient=true \
  -s standardFlowEnabled=true \
  -s directAccessGrantsEnabled=false \
  -s serviceAccountsEnabled=false \
  -s 'attributes."pkce.code.challenge.method"=S256' \
  -s 'redirectUris=["http://localhost:*/*","https://localhost:*/*"]' \
  -s 'webOrigins=["+"]'

echo "== Ajustando audiencia del token al MCP Server (RFC 8707) =="
CLIENT_UUID=$($KCADM get clients -r "$REALM" -q clientId="$CLIENT_ID" --fields id --format csv --noquotes | tail -1)

$KCADM create clients/"$CLIENT_UUID"/protocol-mappers/models -r "$REALM" \
  -s name=jde-mcp-audience \
  -s protocol=openid-connect \
  -s protocolMapper=oidc-audience-mapper \
  -s 'config."included.client.audience"='"$CLIENT_ID" \
  -s 'config."id.token.claim"=false' \
  -s 'config."access.token.claim"=true'

echo "== Listo. Client '$CLIENT_ID' creado en el realm '$REALM' =="
echo "Endpoint de metadatos: $KC_URL/realms/$REALM/.well-known/openid-configuration"
