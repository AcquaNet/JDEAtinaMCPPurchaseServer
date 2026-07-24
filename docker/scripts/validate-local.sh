#!/bin/bash
set -uo pipefail

# Smoke test del profile "local" (ver DEPLOYMENT.md, Parte 2b): repite en curl
# los chequeos que se venian haciendo a mano en Postman, para las partes que
# no necesitan un browser. El login interactivo real (Authorization Code +
# PKCE) sigue siendo cosa de Postman/Claude Desktop -- este script usa el
# grant "password" (ROPC) solo para conseguir un token valido de Keycloak sin
# abrir un browser, no reemplaza probar el flujo real de vez en cuando.
#
# Uso:
#   TEST_USERNAME=jgodino TEST_PASSWORD='...' ./validate-local.sh
#   (sin credenciales, salta los tests que requieren token de Keycloak)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(dirname "$SCRIPT_DIR")"
cd "$DOCKER_DIR"

set -a
# shellcheck disable=SC1091
[[ -f .env.local ]] && source .env.local
set +a

KEYCLOAK_URL="${KC_HOSTNAME:-http://localhost:8180}"
MCP_URL="http://localhost:${MCP_SERVER_PORT:-8080}"
REALM="jde-integration"
CLIENT_ID="atina-mcp-server"
ISSUER="${MCP_KEYCLOAK_ISSUER_URI:-${KEYCLOAK_URL}/realms/${REALM}}"

PASS=0
FAIL=0
SKIP=0

# check DESCRIPCION STATUS_ESPERADO STATUS_REAL [BODY_CONTAINS]
check() {
  local desc="$1" expected="$2" actual="$3" body="${4:-}" needle="${5:-}"
  if [[ "$actual" != "$expected" ]]; then
    echo "FAIL  $desc (esperaba $expected, recibi $actual)"
    FAIL=$((FAIL + 1))
    return 1
  fi
  if [[ -n "$needle" ]] && ! grep -q "$needle" <<<"$body"; then
    echo "FAIL  $desc (status $actual OK, pero no encontre '$needle' en el body)"
    FAIL=$((FAIL + 1))
    return 1
  fi
  echo "PASS  $desc"
  PASS=$((PASS + 1))
  return 0
}

skip() {
  echo "SKIP  $1"
  SKIP=$((SKIP + 1))
}

echo "== Contra: MCP=$MCP_URL  Keycloak=$KEYCLOAK_URL  issuer=$ISSUER =="
echo

# --- Fase de discovery (sin token) ---

resp=$(curl -s -i -X POST "$MCP_URL/mcp" \
  -H 'Accept: application/json, text/event-stream' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"validate-local","version":"1.0"}}}')
status=$(head -1 <<<"$resp" | awk '{print $2}')
check "01 initialize sin token -> 401" 401 "$status" "$resp"
if ! grep -qi 'WWW-Authenticate:.*resource_metadata=' <<<"$resp"; then
  echo "FAIL  01b header WWW-Authenticate con resource_metadata"
  FAIL=$((FAIL + 1))
else
  echo "PASS  01b header WWW-Authenticate con resource_metadata"
  PASS=$((PASS + 1))
fi

resp=$(curl -s -w '\n%{http_code}' "$MCP_URL/.well-known/oauth-protected-resource")
status=$(tail -1 <<<"$resp")
body=$(sed '$d' <<<"$resp")
check "02 protected-resource-metadata -> 200" 200 "$status" "$body" "\"$MCP_URL/mcp\""
if grep -q ngrok <<<"$body"; then
  echo "FAIL  02b metadata no debe apuntar a ngrok en profile local"
  FAIL=$((FAIL + 1))
else
  echo "PASS  02b metadata no apunta a ngrok"
  PASS=$((PASS + 1))
fi

resp=$(curl -s -w '\n%{http_code}' "$KEYCLOAK_URL/realms/$REALM/.well-known/openid-configuration")
status=$(tail -1 <<<"$resp")
body=$(sed '$d' <<<"$resp")
check "03 keycloak discovery -> 200" 200 "$status" "$body" "\"issuer\":\"$ISSUER\""

echo

# --- Fase con token de Keycloak (requiere credenciales) ---

if [[ -z "${TEST_USERNAME:-}" || -z "${TEST_PASSWORD:-}" ]]; then
  skip "04-07 tests con token de Keycloak (setear TEST_USERNAME/TEST_PASSWORD)"
else
  token_resp=$(curl -s -X POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "username=$TEST_USERNAME" \
    --data-urlencode "password=$TEST_PASSWORD")
  TOKEN=$(python3 -c "import json,sys;print(json.load(sys.stdin).get('access_token',''))" <<<"$token_resp" 2>/dev/null)

  if [[ -z "$TOKEN" ]]; then
    echo "FAIL  04 obtener token de Keycloak (password grant) -- $(python3 -c "import json,sys;print(json.load(sys.stdin).get('error_description','?'))" <<<"$token_resp" 2>/dev/null)"
    FAIL=$((FAIL + 1))
    skip "05-07 (sin token no se puede seguir)"
  else
    echo "PASS  04 obtener token de Keycloak (password grant)"
    PASS=$((PASS + 1))

    resp=$(curl -s -i -X POST "$MCP_URL/mcp" \
      -H 'Accept: application/json, text/event-stream' -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $TOKEN" \
      -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"validate-local","version":"1.0"}}}')
    status=$(head -1 <<<"$resp" | awk '{print $2}')
    SESSION_ID=$(grep -i '^Mcp-Session-Id:' <<<"$resp" | tr -d '\r' | cut -d' ' -f2)
    check "05 initialize con token Keycloak -> 200" 200 "$status" "$resp"
    if [[ -z "$SESSION_ID" ]]; then
      echo "FAIL  05b header Mcp-Session-Id presente"
      FAIL=$((FAIL + 1))
    else
      echo "PASS  05b header Mcp-Session-Id presente ($SESSION_ID)"
      PASS=$((PASS + 1))
    fi

    if [[ -n "$SESSION_ID" ]]; then
      status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$MCP_URL/mcp" \
        -H 'Accept: application/json, text/event-stream' -H 'Content-Type: application/json' \
        -H "Mcp-Session-Id: $SESSION_ID" -H "Authorization: Bearer $TOKEN" \
        -d '{"jsonrpc":"2.0","method":"notifications/initialized"}')
      check "06 notifications/initialized -> 202" 202 "$status"

      resp=$(curl -s -w '\n%{http_code}' -X POST "$MCP_URL/mcp" \
        -H 'Accept: application/json, text/event-stream' -H 'Content-Type: application/json' \
        -H "Mcp-Session-Id: $SESSION_ID" -H "Authorization: Bearer $TOKEN" \
        -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
      status=$(tail -1 <<<"$resp")
      body=$(sed '$d' <<<"$resp")
      check "07 tools/list -> 200" 200 "$status" "$body" '"tools"'
    fi
  fi
fi

echo

# --- Bypass de Atina (HS256), sin pasar por Keycloak ---

if [[ -z "${ATINA_JWT_SECRET:-}" ]]; then
  skip "08 bypass de Atina (ATINA_JWT_SECRET no seteado en .env.local)"
else
  b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
  header='{"alg":"HS256"}'
  payload=$(python3 -c "import json,time,random;print(json.dumps({'jti':str(random.randint(1,999999)),'iat':int(time.time()),'sub':'Subject','iss':'Issue','user':'VALIDATE-LOCAL','environment':'JDV920','role':'*ALL','sessionId':-1,'addressBookNumber':'0'}))")
  h64=$(printf '%s' "$header" | b64url)
  p64=$(printf '%s' "$payload" | b64url)
  signing_input="${h64}.${p64}"
  secret_hex=$(printf '%s' "$ATINA_JWT_SECRET" | openssl base64 -d -A | xxd -p -c 256)
  sig=$(printf '%s' "$signing_input" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$secret_hex" -binary | b64url)
  ATINA_TOKEN="${signing_input}.${sig}"

  status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$MCP_URL/mcp" \
    -H 'Accept: application/json, text/event-stream' -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $ATINA_TOKEN" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"validate-local","version":"1.0"}}}')
  check "08 initialize con token de Atina (HS256) -> 200" 200 "$status"
fi

echo
echo "== Resultado: $PASS OK, $FAIL fallidos, $SKIP saltados =="
[[ "$FAIL" -eq 0 ]]
