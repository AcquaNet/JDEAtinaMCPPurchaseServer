#!/usr/bin/env bash
#
# Alta manual de un usuario en el Identity Bridge (desarrollo).
# 1) Guarda la credencial JDE en OpenBao (KV v2, path secret/data/jde/<JDE_USER>)
# 2) Inserta/actualiza el mapeo keycloak_sub -> jde_user en la tabla identity_mapping (H2)
#
# Uso:
#   BAO_TOKEN=... ./scripts/seed-identity-dev.sh <keycloak_sub> <jde_user> <jde_password> [jde_environment] [jde_role]
#
# Ejemplo:
#   BAO_TOKEN=dev-only-root-token ./scripts/seed-identity-dev.sh \
#       ad27ed8d-849a-4f9e-a793-217bb38996d8 JDE 'Modus2020!' JDV920 '*ALL'
#
# El sub de un usuario Keycloak se ve en la consola de Keycloak
# (realm jde-integration -> Users -> <user> -> campo ID).

set -euo pipefail

KEYCLOAK_SUB="${1:?Falta keycloak_sub}"
JDE_USER="${2:?Falta jde_user}"
JDE_PASSWORD="${3:?Falta jde_password}"
JDE_ENV="${4:-JDV920}"
JDE_ROLE="${5:-*ALL}"

BAO_ADDR="${BAO_ADDR:-http://localhost:8200}"
BAO_TOKEN="${BAO_TOKEN:?Falta BAO_TOKEN en el entorno}"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
H2_JAR="$(find ~/.m2/repository/com/h2database -name 'h2-*.jar' | head -1)"
JAVA_BIN="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.1-tem}/bin/java"

echo "==> Guardando credencial en OpenBao ($BAO_ADDR/v1/secret/data/jde/$JDE_USER)"
curl -sf -X POST "$BAO_ADDR/v1/secret/data/jde/$JDE_USER" \
  -H "X-Vault-Token: $BAO_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"data\":{\"user\":\"$JDE_USER\",\"password\":\"$JDE_PASSWORD\"}}" > /dev/null

echo "==> Insertando mapeo en identity_mapping ($KEYCLOAK_SUB -> $JDE_USER/$JDE_ENV/$JDE_ROLE)"
"$JAVA_BIN" -cp "$H2_JAR" org.h2.tools.Shell \
  -url "jdbc:h2:file:$REPO_DIR/data/identity-mapping;MODE=PostgreSQL;AUTO_SERVER=TRUE" \
  -user sa -password "" \
  -sql "MERGE INTO identity_mapping (keycloak_sub, source_mode, jde_user, jde_environment, jde_role, updated_at) KEY (keycloak_sub) VALUES ('$KEYCLOAK_SUB','NATIVE','$JDE_USER','$JDE_ENV','$JDE_ROLE', now());"

echo "==> Listo. El usuario puede operar sin jde_login manual."
