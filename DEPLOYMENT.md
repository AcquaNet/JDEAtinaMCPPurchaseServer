# Guía de Despliegue — JDE MCP Server

Guía paso a paso: cómo correr el stack localmente (con **ngrok** para demos con
login OAuth real), cómo empaquetarlo para llevarlo a **otra PC**, y — más
adelante — cómo desplegarlo en **Digital Ocean**. Pensada para poder repetirse
sin tener que reconstruir el razonamiento cada vez.

Para referencia rápida de qué archivo hace qué dentro de `docker/`, ver
[docker/README.md](docker/README.md) — esta guía es el recorrido completo.

---

## Índice

- [Arquitectura](#arquitectura)
- [Parte 1: Preparar tu máquina](#parte-1-preparar-tu-máquina)
- [Parte 2: Demo con ngrok (login OAuth real)](#parte-2-demo-con-ngrok-login-oauth-real)
- [Parte 2b: Probar el contenedor sin ngrok (profile `local`)](#parte-2b-probar-el-contenedor-sin-ngrok-profile-local)
- [Parte 3: Empaquetar y llevar a otra PC](#parte-3-empaquetar-y-llevar-a-otra-pc)
- [Parte 4: Digital Ocean (prod)](#parte-4-digital-ocean-prod)
- [Keycloak: exportar/importar el realm](#keycloak-exportarimportar-el-realm)
- [Troubleshooting](#troubleshooting)

---

## Arquitectura

```
                         Internet
                            │
                 (solo prod: 80/443)
                            ▼
                        ┌────────┐
                        │ Caddy  │  <- HTTPS automático (Let's Encrypt), SOLO en prod
                        └───┬────┘
              ┌─────────────┼─────────────┐
              ▼                           ▼
     mcp-server:8080                keycloak:8080
     (JDE MCP Server)               (OAuth2 / login)
              │                           │
              ▼                           ▼
        openbao:8200               keycloak-db (Postgres)
     (vault credenciales JDE)
```

Tres ambientes = tres **Docker Compose profiles** del mismo
`docker/docker-compose.yml` (mismo nombre que `spring.profiles.active` de la app):

| Profile | Cuándo se usa |
|---|---|
| `dev` | Tu máquina (esta guía, Partes 1-3) |
| `stage` | Solo si existe un ambiente de staging real de JDE/Mulesoft (ver nota en Parte 1) |
| `prod` | Digital Ocean (Parte 4, más adelante) |

En `dev`/`stage` no hay Caddy — cada servicio se publica por puerto directo,
atado a `127.0.0.1`. Eso es justamente lo que **ngrok** resuelve para las demos
(Parte 2): expone esos puertos por HTTPS público sin necesitar Caddy ni un
dominio propio todavía.

### ¿Por qué `localhost` no alcanza para una demo?

```
SIN ngrok — no funciona para una demo:

  Claude.ai (nube)  ────X────>  http://localhost:8180   Claude.ai jamás puede
                                                          llegar a "localhost"
  Claude Desktop    ───────>   http://localhost:8180    esto sí funciona (mismo
  (browser local)                                        Mac), PERO...

  mcp-server (contenedor) ──X──> http://localhost:8180   "localhost" ahí ES EL
                                                          PROPIO contenedor, no
                                                          llega a Keycloak

CON ngrok — funciona siempre, sea Claude.ai, Desktop, o el mcp-server
containerizado:

  Claude.ai / Desktop ──HTTPS──> https://<tu-dominio>.ngrok...  (Keycloak)
  mcp-server           ──HTTPS──> https://<tu-dominio>.ngrok...  (mismo lugar)

  KC_HOSTNAME  ==  MCP_KEYCLOAK_ISSUER_URI  ==  esa misma URL pública ngrok
```

---

## Parte 1: Preparar tu máquina

### Prerequisitos

- Docker Desktop instalado y corriendo.
- Java 25 + Maven si vas a correr el MCP Server desde el IDE (uso normal de día a día).
- Cuenta de ngrok (gratis alcanza) — [ngrok.com](https://ngrok.com) → Sign up.

### Levantar Keycloak + OpenBao

```bash
cd docker
docker compose --profile dev up -d keycloak-db keycloak openbao
```

> ⚠️ Fijate que acá se listan los 3 servicios explícitamente, **sin**
> `mcp-server`. Si corrés `docker compose --profile dev up -d` a secas, también
> levanta el contenedor `mcp-server`, que ocupa el puerto 8080 igual que
> IntelliJ — van a chocar. Si ya lo levantaste sin querer:
> `docker compose --profile dev stop mcp-server`.

Verificar:

```bash
docker compose --profile dev ps
curl -s http://localhost:8180/realms/jde-integration | jq .realm     # Keycloak
curl -s http://localhost:8200/v1/sys/health | jq .initialized        # OpenBao
```

Ahora corré el MCP Server desde IntelliJ (o `./mvnw spring-boot:run`) — perfil
`dev` activo por defecto, nada nuevo que aprender ahí.

> **`stage`**: existe como profile pero solo tiene sentido si hay un ambiente
> de staging *real y compartido* de JDE/Mulesoft (`application-stage.properties`
> exige esas URLs por variable de entorno, sin fallback a `localhost`). Si no
> existe todavía, ignorá `stage` — no aplica a esta guía.

### Apagar / limpiar

```bash
docker compose --profile dev down          # para los contenedores, conserva los volúmenes
docker compose --profile dev down -v       # además borra volúmenes (¡pierde datos de Keycloak/OpenBao/H2!)
```

---

## Parte 2: Demo con ngrok (login OAuth real)

Usamos **dos terminales, cada una con su propio `ngrok http`** (no el archivo
de config con `ngrok start --all`) — es lo que funciona sin importar la
versión de ngrok instalada. Verificado con `ngrok version 3.36.1`.

> Requiere el MCP Server con `server.forward-headers-strategy=framework`
> (`application.properties`) — si tu checkout es viejo y no lo tiene, `git
> pull` y reconstruir (`docker compose --profile dev build mcp-server`, o
> reiniciar desde el IDE). Sin esto, el login falla con `Protected resource
> http://... does not match expected https://...` porque el server arma la URL
> como `http://` en vez de confiar en el `https://` real que ve ngrok/Caddy.

### Cheat sheet (una vez que ya hiciste el setup inicial una vez)

```bash
# Terminal A (dejar abierta)
ngrok http 8180 --url https://TU-DOMINIO-FIJO.ngrok-free.app    # Keycloak

# Terminal B (dejar abierta)
ngrok http 8080                                                  # MCP Server

# Terminal C
cd docker && docker compose --profile dev up -d

# Copiar la URL que te dio la Terminal B (cambia cada vez) y pegarla en
# Claude Desktop/Claude.ai como https://<esa-url>/mcp
```

Si esto no alcanza (primera vez, o algo cambió), seguí el setup completo abajo.

### Setup inicial (se hace UNA sola vez)

**Paso 1 — Conseguir tu dominio fijo de ngrok** (gratis, uno por cuenta, no
cambia nunca):

1. Entrar a [dashboard.ngrok.com](https://dashboard.ngrok.com) → **Domains**.
2. Ya viene uno asignado a tu cuenta (algo como
   `tu-nombre-random.ngrok-free.app`) — copialo. Si no ves ninguno, "+ Create
   Domain" para reclamarlo.
3. `ngrok config add-authtoken TU_AUTHTOKEN` (una sola vez; el token está en
   [dashboard.ngrok.com/get-started/your-authtoken](https://dashboard.ngrok.com/get-started/your-authtoken)).

**Paso 2 — Levantar los dos túneles, cada uno en su terminal**:

```bash
# Terminal A -- Keycloak, con el dominio fijo del Paso 1
ngrok http 8180 --url https://TU-DOMINIO-FIJO.ngrok-free.app
```

```bash
# Terminal B -- MCP Server, con dominio aleatorio (alcanza con pegarlo en Claude)
ngrok http 8080
```

Dejá las dos terminales abiertas. En la Terminal B vas a ver algo como:

```
Forwarding   https://a1b2c3d4.ngrok-free.app -> http://localhost:8080
```

Esa URL (`a1b2c3d4...`) es la que copiás para Claude en el Paso 6 — **cambia
cada vez que reiniciás** esta terminal, por eso no le pusimos dominio fijo (el
free tier de ngrok da uno solo, y lo reservamos para Keycloak).

> Si preferís un solo comando (`ngrok start --all` con un archivo de config
> `endpoints:`/`version: 3`), es posible en agentes ngrok recientes, pero no
> anduvo con la instalación con la que probamos esto — si te tienta ahorrarte
> las dos terminales, probalo, y si da el error `must define at least one
> tunnel`, volvé a las dos terminales de arriba (funciona siempre).

**Paso 3 — Configurar Keycloak y el MCP Server con la URL fija** (una sola vez;
como `KC_HOSTNAME` queda guardado en `docker/.env`, no hace falta repetir esto
en cada demo):

Editar `docker/.env` y agregar/reemplazar:

```bash
KC_HOSTNAME=https://TU-DOMINIO-FIJO.ngrok-free.app
MCP_KEYCLOAK_ISSUER_URI=https://TU-DOMINIO-FIJO.ngrok-free.app/realms/jde-integration
```

Si vas a correr el MCP Server **desde IntelliJ** (lo más común), agregá esa
misma variable al Run Configuration (Environment variables):
```
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://TU-DOMINIO-FIJO.ngrok-free.app/realms/jde-integration
```

Recrear Keycloak para que tome el nuevo hostname:

```bash
cd docker
docker compose --profile dev up -d --force-recreate keycloak
```

**Paso 4 — Agregar el redirect URI de ngrok al client de Keycloak** (para que
el login no falle con "invalid redirect_uri"):

1. `https://TU-DOMINIO-FIJO.ngrok-free.app/admin` → login (admin / la password de `docker/.env`).
2. Realm `jde-integration` → Clients → `atina-mcp-server` → **Valid redirect URIs**
   → agregar la URL de callback que en algún momento te va a mostrar el error de
   Claude.ai/Desktop (la primera vez que intentes loguearte va a fallar UNA vez
   mostrando exactamente cuál falta — se agrega esa y listo).
3. Guardar, y exportar el realm para no perder el cambio si se recrea el
   contenedor (ver [Keycloak: exportar/importar](#keycloak-exportarimportar-el-realm)).

**Paso 5 — Conectar Claude Desktop**: Claude Desktop **no acepta** la forma
`url` + `headers` directa (config remota pura) — hay que usar `command` +
`args`, que lanza `mcp-remote` (bridge stdio↔HTTP) como proceso local. Editar
`claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "jde-atina": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote@latest",
        "https://<url-de-la-terminal-b>/mcp",
        "--static-oauth-client-info",
        "{ \"client_id\": \"atina-mcp-server\" }"
      ]
    }
  }
}
```

El flag `--static-oauth-client-info` es clave: sin él, `mcp-remote` intenta
**Dynamic Client Registration** (registrar un client nuevo contra Keycloak) y
la política `Allowed Client Scopes` del realm lo rechaza
(`InsufficientScopeError`, ver [Troubleshooting](#troubleshooting)). Con este
flag, usa directo el client `atina-mcp-server` que ya existe en el realm y
hace el login real (Authorization Code + PKCE, browser) sin registrar nada.

Reiniciar Claude Desktop del todo (Quit, no solo cerrar la ventana) después de
editar el config, y completar el login la primera vez que te lo pida.

### Qué se repite en cada demo (a partir de acá)

Solo esto — todo lo del setup inicial queda guardado:

1. Abrir las dos terminales (Paso 2) y dejarlas corriendo.
2. `docker compose --profile dev up -d` (o correr el MCP Server desde IntelliJ).
3. La URL de la **Terminal B** (mcp-server) cambia cada vez — copiarla y
   actualizarla dentro de `args` en `claude_desktop_config.json` (Paso 5), y
   reiniciar Claude Desktop. La de la **Terminal A** (keycloak) no cambia
   nunca (dominio fijo del Paso 1).

> **Si en vez de la demo querés probar tools sin login interactivo**, cambiá
> el `--static-oauth-client-info ...` del config de arriba por
> `--header "Authorization: Bearer <token-de-atina>"` con un token real de
> Atina (HS256, ver AUTHENTICATION.md) — no necesita Keycloak en absoluto (sí
> necesita igual el túnel de ngrok de la Terminal B para llegar al MCP
> Server). Útil para validar rápido que el server/túnel responden, sin pasar
> por el login.

> **Alternativa si la demo puede esperar**: si para entonces ya tenés Digital
> Ocean levantado (Parte 4), usalo directo — dominios reales, sin túneles que
> mantener corriendo.

---

## Parte 2b: Probar el contenedor sin ngrok (profile `local`)

Para aislar si un error es causado por ngrok (latencia, buffering del túnel,
etc.), hay un profile `local`: mismo stack que `dev`, pero con el MCP Server
**containerizado** (no vía IDE) y sin ningún túnel — Keycloak, OpenBao y el
MCP Server se acceden todos por `localhost` desde el host (Postman, browser).

> Requiere el MCP Server con el desacople `issuer-uri`/`jwks-uri` en
> `SecurityConfig.keycloakDecoder` (property `jde.mcp.security.keycloak-jwks-uri`)
> — si tu checkout es viejo y no lo tiene, `git pull`.

**Por qué hace falta esto y no alcanza con `KC_HOSTNAME=http://localhost:8180`
a secas**: el navegador/Postman corren en el host y necesitan `localhost:8180`
para llegar a Keycloak, pero el contenedor de `mcp-server` no puede resolver
`localhost` como si fuera el host (ahí "localhost" es el propio contenedor) ni
tampoco `host.docker.internal` desde el lado del host (solo resuelve *dentro*
de contenedores). Sin ngrok no hay un único hostname que sirva para los dos
lados. La solución: el `issuer-uri` (lo que Keycloak firma en `iss`, y lo que
se valida contra el token) queda en `http://localhost:8180/...` — alcanzable
desde el host —, pero el `jwks-uri` (de dónde el contenedor trae la clave
pública para validar la firma) se fija aparte a la ruta interna de Docker
Compose (`http://keycloak:8080/...`) vía `KEYCLOAK_JWKS_URI`.

**Setup** (`docker/.env.local` ya viene con esto):

```bash
# docker/.env.local
KC_HOSTNAME=http://localhost:8180
MCP_KEYCLOAK_ISSUER_URI=http://localhost:8180/realms/jde-integration
KEYCLOAK_JWKS_URI=http://keycloak:8080/realms/jde-integration/protocol/openid-connect/certs
```

**Levantar**: usar siempre `docker/scripts/up-local.sh`, no `docker compose
--profile local up` a mano. El flag `--env-file .env.local` es obligatorio
(sin él, Compose cae en silencio a `docker/.env`, con la URL de ngrok, y no
avisa del error) — ya pasó dos veces en este proyecto, incluso sabiéndolo. El
script fuerza el `--env-file` y el `--build` (para tomar siempre el código
actual, no una imagen de registry vieja — otro error ya cometido: `.env.local`
llegó a tener `MCP_IMAGE=92455890/jde-mcp-server:1.0.0` apuntando a un tag
pusheado antes de un fix), y verifica solo que el resultado no sea ngrok:

```bash
cd docker
./scripts/up-local.sh              # todos los servicios del profile
./scripts/up-local.sh mcp-server   # o solo un servicio
```

Si por lo que sea corrés el `docker compose` a mano, verificar con:

```bash
# Debe anunciar localhost:8180, no la URL de ngrok
curl -s http://localhost:8080/.well-known/oauth-protected-resource

# Debe dar 200 -- confirma que el contenedor llega al jwks-uri interno
docker compose --profile local exec mcp-server curl -s -o /dev/null -w '%{http_code}\n' \
  http://keycloak:8080/realms/jde-integration/protocol/openid-connect/certs
```

Con eso, Postman puede hacer el flujo OAuth2.1 completo contra
`http://localhost:8180/realms/jde-integration` sin ningún túnel de por medio.

### Validación automática (`validate-local.sh`)

Repite en `curl` los mismos chequeos que se venían haciendo a mano en Postman
(fase de discovery sin token, protected-resource-metadata, discovery de
Keycloak, handshake MCP completo con token real, y el bypass de Atina) — todo
lo que no requiere un browser. El login interactivo real (Authorization Code +
PKCE) sigue siendo cosa de Postman/Claude Desktop de vez en cuando; este
script no lo reemplaza, cubre el resto para no tener que clickear cada vez:

```bash
cd docker
./scripts/validate-local.sh                                          # sin credenciales: salta los tests con token de Keycloak
TEST_USERNAME=jgodino TEST_PASSWORD='...' ./scripts/validate-local.sh # corre todo
```

> ⚠️ Si alguno de estos tests falla apuntando a la URL de ngrok, es señal de
> que el contenedor se recreó con `docker compose ... up` a mano (sin
> `--env-file .env.local`) en algún momento — volver a levantar con
> `up-local.sh` y repetir.

---

## Parte 3: Empaquetar y llevar a otra PC

La otra PC **no necesita** Java, Maven, ni el código fuente — solo Docker y la
carpeta `docker/`. La imagen del MCP Server se construye acá (esta máquina, que
tiene el JDK/Maven) y se sube a un registry; la otra PC solo la descarga.

### En esta máquina

**1. Build y push de la imagen** (necesitás una cuenta en Docker Hub, o
cualquier otro registry — `docker login` primero):

> ⚠️ **La imagen tiene que salir multi-arquitectura, siempre.** El servicio
> `mcp-server` en `docker-compose.yml` ya declara `build.platforms:
> [linux/amd64, linux/arm64]`, así que `docker compose build` arma para las
> dos por default — no hace falta pensarlo tag a tag. Esto reemplaza un
> `docker buildx build --platform ...` a mano que se nos olvidó usar dos
> veces seguidas (pusheamos solo `arm64`, la arquitectura de esta Mac, y el
> pull fallaba en Windows/`amd64` con `manifest unknown`). Requiere un
> builder con driver `docker-container` (no el `desktop-linux` default, que
> no soporta multi-plataforma) — crearlo una sola vez por máquina:
> ```bash
> docker buildx create --name multiarch-builder --driver docker-container
> ```

```bash
# Desde docker/. MCP_IMAGE define el tag (ver docker/.env); default
# jde-mcp-server:latest si no está seteada.
cd docker
MCP_IMAGE=92455890/jde-mcp-server:TAG \
  docker compose --profile dev build --builder multiarch-builder --push mcp-server
```

Verificar que el push haya quedado multi-arquitectura antes de darlo por
bueno (debe listar `amd64` y `arm64`, no solo uno):

```bash
docker buildx imagetools inspect 92455890/jde-mcp-server:TAG
```

> ⚠️ **No correr después `docker compose --profile dev up -d --build
> mcp-server` (ni `docker build`/`docker push` sueltos) con ese mismo
> `MCP_IMAGE`.** `up --build` arma la imagen SOLO para la arquitectura de
> esta Mac (para poder correrla local, no puede "levantar" un manifest
> multi-plataforma) y la deja local con ese mismo nombre — un `docker push`
> posterior de esa imagen (a mano, o por costumbre) pisa el manifest
> multi-arquitectura bueno con uno single-arch, y el pull vuelve a fallar en
> Windows/Linux. Esto ya pasó. Para correr/probar la imagen local, usar un
> `MCP_IMAGE` que **no** sea el que ya pusheaste (por ejemplo, sin setear la
> variable, que cae al default `jde-mcp-server:latest` sin el namespace de
> Docker Hub) — el único comando que debe tocar el tag real (`92455890/...`)
> es el `docker compose build --push` de arriba.

**2. Armar el ZIP con solo lo necesario** (ya hay un script para esto):

```bash
cd docker
./scripts/package.sh
```

Genera `jde-mcp-server-docker-YYYYMMDD.zip` en la raíz del repo, con:
`docker-compose.yml`, `.env` (placeholders de dev, sin secretos reales),
`.env.stage.example`, `.env.prod.example`, `Caddyfile.prod`,
`realm-export.json` + `export-realm.sh`, `deploy.sh`, el `Dockerfile`, y esta
misma guía (`DEPLOYMENT.md`). **No** incluye `.env.stage`/`.env.prod`/
`deploy.env` reales (secretos) — la otra PC arranca esos desde los `.example`.

**3. Transferir el ZIP** a la otra PC (AirDrop, USB, `scp`, lo que sea).

### En la otra PC

```bash
unzip jde-mcp-server-docker-*.zip
cd jde-mcp-server-docker

# Editar .env con MCP_IMAGE apuntando a la imagen pusheada:
#   MCP_IMAGE=<tu-usuario-dockerhub>/jde-mcp-server:dev

docker compose --profile dev up -d keycloak-db keycloak openbao
docker compose --profile dev pull mcp-server
docker compose --profile dev up -d mcp-server
```

Para la demo con ngrok en esa PC: repetir el setup de la
[Parte 2](#parte-2-demo-con-ngrok-login-oauth-real) ahí (ngrok tunelea
`localhost` de la máquina donde corre, así que hay que instalarlo y
configurarlo también en la otra PC — el dominio fijo de tu cuenta ngrok sirve
igual desde cualquier máquina donde inicies sesión).

> Si la otra PC no va a tener internet/acceso al registry, alternativa 100%
> offline: `docker save <imagen> -o mcp-server.tar` en esta máquina, incluir
> ese `.tar` en el ZIP a mano, y en la otra PC `docker load -i mcp-server.tar`
> antes de `docker compose up` (sin necesidad de `pull`).

---

## Parte 4: Digital Ocean (prod)

> Retomar esta parte una vez que la demo con ngrok (Parte 2) esté validada.

Dominio: **`jdemcp-atina-connection.com`** (MCP Server) y
**`auth.jdemcp-atina-connection.com`** (Keycloak).

### Prerequisitos

- [ ] Droplet de Digital Ocean con Docker y Docker Compose Plugin instalados
      (Ubuntu + `curl -fsSL https://get.docker.com | sh` es lo más simple).
- [ ] Acceso SSH al droplet (clave configurada).
- [ ] DNS: dos registros A apuntando a la IP del droplet:
  - `jdemcp-atina-connection.com` → IP del droplet
  - `auth.jdemcp-atina-connection.com` → IP del droplet
- [ ] Puertos **80 y 443** abiertos en el firewall del droplet (Let's Encrypt
      valida el dominio por HTTP-01 en el puerto 80, y Caddy sirve HTTPS en 443).
- [ ] Verificar el DNS *antes* de desplegar:
  ```bash
  dig +short jdemcp-atina-connection.com
  dig +short auth.jdemcp-atina-connection.com
  # ambos deben devolver la IP del droplet
  ```

### Paso 1 — Build y push de la imagen del MCP Server

> **Mac → Linux**: los droplets "estándar" de Digital Ocean son `linux/amd64`
> (Intel/AMD), aunque tu Mac sea Apple Silicon (ARM) o Intel. El `build.platforms`
> de `docker-compose.yml` ya incluye `linux/amd64` (y de paso `linux/arm64`,
> por si en algún momento usás un droplet ARM "Ampere" o corrés la imagen en
> esta misma Mac) -- corre bien en el droplet sin importar la arquitectura de
> la máquina donde se buildea (usa emulación QEMU si hace falta, tarda más
> que un build single-arch).

Mismo comando y mismo builder que en la [Parte 3](#parte-3-empaquetar-y-llevar-a-otra-pc)
(crear el builder una sola vez por máquina si todavía no existe):

```bash
docker buildx create --name multiarch-builder --driver docker-container   # una sola vez

cd docker
MCP_IMAGE=<tu-usuario-o-registry>/jde-mcp-server:prod \
  docker compose --profile prod build --builder multiarch-builder --push mcp-server
```

### Paso 2 — Preparar la carpeta en el droplet

```bash
# En tu máquina: copiar solo docker/ al droplet
scp -r docker/ root@<IP-DROPLET>:/root/jde-mcp-server-docker

# O por git sparse-checkout, si el droplet tiene acceso al repo:
ssh root@<IP-DROPLET>
git clone <url-del-repo> --sparse --filter=blob:none
cd JDEAtinaMCPPurchaseServer && git sparse-checkout set docker Dockerfile
```

### Paso 3 — Completar `.env.prod`

```bash
ssh root@<IP-DROPLET>
cd /root/jde-mcp-server-docker
cp .env.prod.example .env.prod
nano .env.prod   # completar todos los CAMBIAR-...
```

Valores a completar (ver comentarios en el archivo):

| Variable | Qué es |
|---|---|
| `KC_DB_PASSWORD`, `KC_ADMIN_PASSWORD` | Passwords fuertes nuevas (no las de dev) |
| `OPENBAO_ROOT_TOKEN` / `BAO_TOKEN` | Root token de OpenBao (ideal: un token acotado, no el root, ver Seguridad) |
| `ATINA_JWT_SECRET` | Secreto compartido con el microservicio de Atina (Base64 estándar) |
| `JDE_MULESOFT_BASE_URL` | URL real de Mulesoft en producción |
| `JDE_ATINA_GATEWAY_BASE_URL` | URL real del Gateway de Atina en producción |
| `MCP_IMAGE` | El tag que pusheaste en el Paso 1 |

`KC_HOSTNAME` y `MCP_KEYCLOAK_ISSUER_URI` ya vienen completos en el template
apuntando a `auth.jdemcp-atina-connection.com` — **no los cambies** a menos que
cambies el dominio, y si lo hacés, cambialos **juntos** (ver nota abajo).

> ⚠️ **`MCP_KEYCLOAK_ISSUER_URI` tiene que ser idéntico a `KC_HOSTNAME`**
> (mismo principio que en la demo con ngrok, Parte 2). Keycloak firma el claim
> `iss` de cada token con su propio `KC_HOSTNAME`; si el MCP Server espera un
> issuer distinto, rechaza **todos** los tokens (401 en todo). Es el error más
> fácil de cometer acá — revisar dos veces si algo devuelve 401 inesperadamente.

### Paso 4 — Levantar el stack

```bash
docker compose --profile prod --env-file .env.prod pull
docker compose --profile prod --env-file .env.prod up -d
```

La primera obtención del certificado Let's Encrypt tarda ~30 segundos. Verificar:

```bash
docker compose --profile prod --env-file .env.prod logs caddy
```

### Paso 5 — Verificación end-to-end

- [ ] `curl https://jdemcp-atina-connection.com/.well-known/oauth-protected-resource` → JSON (no error de certificado)
- [ ] `curl https://auth.jdemcp-atina-connection.com/realms/jde-integration` → JSON del realm
- [ ] Conectar Claude Desktop/Claude.ai al MCP Server (`https://jdemcp-atina-connection.com/mcp`) y completar el login OAuth
- [ ] Si Claude.ai devuelve error de `redirect_uri` inválido: la consola admin de
      Keycloak (`https://auth.jdemcp-atina-connection.com/admin`) → Clients →
      `atina-mcp-server` → agregar la URL exacta que informa el error en
      "Valid redirect URIs" → volver a exportar el realm
      (ver [Keycloak: exportar/importar](#keycloak-exportarimportar-el-realm))
      para no perder el cambio si se recrea el contenedor.
- [ ] Dar de alta el usuario/mapeo de identidad real (Identity Bridge) si no
      vino incluido en `realm-export.json` — ver `scripts/seed-identity-dev.sh`
      como referencia para el alta en OpenBao + `identity_mapping`.

### Redeploys (nueva versión del MCP Server)

```bash
cd docker
cp deploy.env.example deploy.env   # una vez: completar DO_HOST/DO_USERNAME/DO_SSH_KEY_PATH/REMOTE_DIR

# 1) build + push de la nueva imagen (Paso 1)
# 2) redeploy:
./scripts/deploy.sh
```

Esto solo reinicia `mcp-server` — Keycloak/OpenBao/Caddy no se tocan.

---

## Keycloak: exportar/importar el realm

Para no reconfigurar Keycloak a mano cada vez que se levanta un ambiente nuevo:

- **Import**: automático. `docker-compose.yml` corre `start-dev --import-realm`
  con `docker/keycloak/realm-export.json` montado en
  `/opt/keycloak/data/import/`. Si el realm `jde-integration` ya existe en la
  base (`keycloak-db`), no hace nada — solo importa en un Keycloak realmente
  nuevo (o si se borra el volumen `keycloak_db_data`).

- **Export**: manual, cuando cambiaste algo a mano en la consola admin
  (roles, mappers, redirect URIs, usuarios) y querés capturarlo:

  ```bash
  cd docker/keycloak
  KC_DB_PASSWORD=<el-password-en-uso> ./export-realm.sh
  git diff -- realm-export.json   # revisar antes de commitear
  ```

  El script para brevemente el contenedor `keycloak` (Keycloak no exporta de
  forma confiable mientras sirve tráfico), exporta contra la misma base
  Postgres vía un contenedor temporal, y lo reinicia al terminar.

  **Incluye usuarios** (`--users realm_file`): email real y password
  *hasheada* (bcrypt/pbkdf2, nunca en texto plano) quedan en el JSON
  committeado, por decisión explícita. Si el repo llegara a compartirse más
  ampliamente o hacerse público, reconsiderar esto (cambiar a `--users skip`
  en `export-realm.sh`).

---

## Troubleshooting

| Síntoma | Causa probable | Verificar |
|---|---|---|
| Keycloak no arranca: `Invalid value for option 'KC_PROXY_HEADERS'` | Variable pasada vacía en vez de omitida | Ya corregido: viene fijo en `xforwarded` en `docker-compose.yml`, no depende de ninguna variable |
| `ngrok start --all` → `must define at least one tunnel` | El archivo de config (`endpoints:`/`version: 3`) no es compatible con tu versión de ngrok instalada | Usar el método de dos terminales (`ngrok http <puerto>` x2) de la Parte 2 — no depende de ningún archivo de config |
| Login OAuth no funciona (dev/stage sin ngrok) | Limitación conocida — ver [Parte 2](#parte-2-demo-con-ngrok-login-oauth-real) | Usar ngrok, o el IDE + Claude Desktop en la misma máquina sin contenedor |
| Claude no puede completar el login con ngrok | Redirect URI no autorizado, o `KC_HOSTNAME`/`MCP_KEYCLOAK_ISSUER_URI` no coinciden | Ver Parte 2, Paso 3 y 4 |
| `mcp-remote`: `Protected resource http://...` does not match expected `https://...` | El MCP Server no confía en `X-Forwarded-Proto` del proxy (ngrok/Caddy) y arma la URL como `http://` en vez de `https://` | Ya corregido: `server.forward-headers-strategy=framework` en `application.properties`. Si ves esto, actualizá el código (`git pull`) y reconstruí: `docker compose --profile dev build mcp-server` (o reiniciar desde el IDE) |
| `mcp-remote`: `Discovered authorization server: http://keycloak:8080/...` | El MCP Server todavía apunta al issuer interno de Docker, no al túnel ngrok de Keycloak | Completar Parte 2, Paso 3 (`KC_HOSTNAME`/`MCP_KEYCLOAK_ISSUER_URI` = URL fija de ngrok) y recrear `keycloak` |
| `mcp-remote`: `InsufficientScopeError: Policy 'Allowed Client Scopes' rejected request to client-registration service` | `mcp-remote` no tiene un client_id fijo configurado y por default intenta Dynamic Client Registration contra Keycloak, que el realm rechaza | Agregar `--static-oauth-client-info '{ "client_id": "atina-mcp-server" }'` al `args` del config de Claude Desktop (ver Parte 2, Paso 5) — usa el client que ya existe en vez de registrar uno nuevo |
| Token de Atina real da `401` en Docker/ngrok pero `200` en una instancia local | `ATINA_JWT_SECRET` distinto entre `docker/.env` y el entorno donde corre la instancia local (firma HS256 no matchea) | Poner el mismo `ATINA_JWT_SECRET` real (el del microservicio de Atina) en `docker/.env` y recrear `mcp-server` |
| `docker pull`/`docker compose pull` en otra PC: `manifest unknown` o `no matching manifest for linux/amd64 in the manifest list entries` | La imagen se pusheó single-arch (solo la arquitectura de la máquina que la buildeó, ej. `arm64` en Mac Apple Silicon) en vez de multi-arquitectura | Rebuildear y pushear con Parte 3, Paso 1 (`docker compose build --builder multiarch-builder --push`); confirmar con `docker buildx imagetools inspect <imagen>:<tag>` que lista `amd64` **y** `arm64` antes de reintentar el pull. Ojo: correr después `docker compose up -d --build` con el mismo tag y volver a pushearlo manualmente pisa el manifest bueno con uno single-arch de nuevo |
| `docker compose --profile local up` usa la URL de ngrok en vez de `localhost` (sin avisar) | Falta el flag `--env-file .env.local` — Compose cae en silencio a `docker/.env` (el de `dev`, con ngrok) | Usar `docker/scripts/up-local.sh` (ver Parte 2b), que fuerza el `--env-file` correcto y verifica solo que no haya quedado apuntando a ngrok |
| Keycloak: `LOGIN_ERROR ... error="ssl_required"` / página "HTTPS required" al hacer login (Postman, `mcp-remote`) | El realm tiene `sslRequired: "external"`. Keycloak exige que el `redirect_uri` del cliente sea `https://` en cuanto ve que la conexión viene de una IP "no local" (`X-Forwarded-For`, siempre así detrás de ngrok o Caddy) -- pero el `redirect_uri` de apps nativas/CLI (Postman, `mcp-remote`) es un loopback `http://localhost:<puerto>/...`, correcto según RFC 8252. No es un problema de `X-Forwarded-Proto` (llega bien igual) | Poner `sslRequired: "none"` en el realm (Admin Console → Realm Settings → Login → Require SSL: None, o vía API admin) y volver a exportar (`export-realm.sh`) para que quede persistido. Afecta a **todos** los ambientes (dev/ngrok, local, prod) por igual -- ya está corregido en `realm-export.json` |
| Caddy no obtiene certificado (prod) | DNS no resuelve al droplet, o puertos 80/443 no accesibles desde internet | `dig +short jdemcp-atina-connection.com`, `curl http://jdemcp-atina-connection.com` desde otra máquina, `docker compose ... logs caddy` |
| Todos los tokens de Keycloak son rechazados (401) | `MCP_KEYCLOAK_ISSUER_URI` no coincide con `KC_HOSTNAME` | Revisar que ambos sean la misma URL pública (ngrok o dominio real) |
| `jde.vault.addr`/`jde.vault.token` fallan | `BAO_TOKEN` no seteado, o token de OpenBao expirado/inválido | `docker compose ... logs openbao`; `curl http://127.0.0.1:8200/v1/sys/health` (o por SSH tunnel en prod) |

### Seguridad

- En `prod`, solo Caddy publica puertos al mundo (80/443). Keycloak, OpenBao y
  `mcp-server` quedan atados a `127.0.0.1` del droplet — para administrarlos
  desde afuera, usar un túnel SSH: `ssh -L 8180:127.0.0.1:8180 root@<IP-DROPLET>`.
- Usar el `OPENBAO_ROOT_TOKEN` solo en dev. En stage/prod, crear una policy de
  OpenBao de solo lectura sobre `secret/data/jde/*` y usar un token acotado a
  esa policy como `BAO_TOKEN`.
- `docker/.env.stage`, `docker/.env.prod` y `docker/deploy.env` están
  gitignored (contienen secretos reales) — solo se commitean los `.example`.
- El ZIP de la Parte 3 tampoco incluye esos archivos con secretos reales.
