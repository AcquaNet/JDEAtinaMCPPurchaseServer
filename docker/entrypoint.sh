#!/bin/sh
# Entrypoint del contenedor mcp-server. Si APP_VERSION esta seteada, descarga
# ese jar desde JFrog Artifactory ANTES de arrancar -- reemplaza el jar
# horneado en la imagen en build time (si lo hay). Esto es lo que permite
# desplegar una version nueva de la app sin reconstruir/pushear/pullear una
# imagen Docker nueva: se hace "mvn deploy" (sube el jar a JFrog), se cambia
# APP_VERSION en el .env del server, y se reinicia el contenedor -- mismo
# imagen, jar nuevo.
#
# Si APP_VERSION no esta seteada (caso tipico de dev/local con
# `docker compose ... --build`), usa el jar ya presente en /app/app.jar
# (horneado en build time por el Dockerfile) sin tocar la red -- comportamiento
# identico al de antes de este mecanismo.
#
# APP_VERSION=latest (o LATEST/Latest, sin distinguir mayusculas) resuelve la
# ultima version publicada via la API de busqueda de Artifactory, en vez de
# pedir un numero de version fijo.
set -eu

APP_JAR="/app/app.jar"

if [ -n "${APP_VERSION:-}" ]; then
  JFROG_URL="${JFROG_URL:?JFROG_URL es requerido cuando APP_VERSION esta seteada}"
  JFROG_REPO="${JFROG_REPO:?JFROG_REPO es requerido cuando APP_VERSION esta seteada}"
  JFROG_GROUP_ID="${JFROG_GROUP_ID:-com.atina}"
  JFROG_ARTIFACT_ID="${JFROG_ARTIFACT_ID:-JDEMCPServer}"

  # Credenciales opcionales -- si el repo de lectura es anonimo, quedan vacias
  # y curl no manda Authorization.
  CURL_AUTH=""
  if [ -n "${JFROG_USERNAME:-}" ]; then
    CURL_AUTH="-u ${JFROG_USERNAME}:${JFROG_PASSWORD:-}"
  fi

  VERSION="${APP_VERSION}"
  case "$VERSION" in
    [Ll][Aa][Tt][Ee][Ss][Tt])
      echo "==> APP_VERSION=latest -- resolviendo la ultima version publicada en ${JFROG_REPO}..."
      VERSION="$(curl -sf ${CURL_AUTH} \
        "${JFROG_URL}/api/search/latestVersion?g=${JFROG_GROUP_ID}&a=${JFROG_ARTIFACT_ID}&repos=${JFROG_REPO}")"
      if [ -z "$VERSION" ]; then
        echo "!! No se pudo resolver 'latest': la API de busqueda de JFrog devolvio vacio." >&2
        echo "!! Verificar que ${JFROG_GROUP_ID}:${JFROG_ARTIFACT_ID} tenga al menos una version publicada en ${JFROG_REPO}." >&2
        exit 1
      fi
      echo "==> Ultima version resuelta: ${VERSION}"
      ;;
  esac

  GROUP_PATH="$(echo "$JFROG_GROUP_ID" | tr '.' '/')"
  JAR_URL="${JFROG_URL}/${JFROG_REPO}/${GROUP_PATH}/${JFROG_ARTIFACT_ID}/${VERSION}/${JFROG_ARTIFACT_ID}-${VERSION}.jar"

  echo "==> Descargando ${JFROG_ARTIFACT_ID} ${VERSION} desde JFrog: ${JAR_URL}"
  if ! curl -sf ${CURL_AUTH} -o "${APP_JAR}.tmp" "${JAR_URL}"; then
    echo "!! Fallo la descarga de ${JAR_URL}" >&2
    echo "!! Verificar APP_VERSION/JFROG_* en el .env y que esa version este publicada." >&2
    rm -f "${APP_JAR}.tmp"
    exit 1
  fi
  mv "${APP_JAR}.tmp" "${APP_JAR}"
  echo "==> Descarga OK ($(du -h "${APP_JAR}" | cut -f1))"
else
  echo "==> APP_VERSION no seteada -- usando el jar horneado en la imagen (build local)"
fi

exec java -jar "${APP_JAR}" "$@"
