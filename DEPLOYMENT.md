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

**Paso 5 — Conectar Claude**: pegar `https://<url-de-la-terminal-b>/mcp`
como URL del servidor MCP en Claude Desktop/Claude.ai, y completar el login.

### Qué se repite en cada demo (a partir de acá)

Solo esto — todo lo del setup inicial queda guardado:

1. Abrir las dos terminales (Paso 2) y dejarlas corriendo.
2. `docker compose --profile dev up -d` (o correr el MCP Server desde IntelliJ).
3. La URL de la **Terminal B** (mcp-server) cambia cada vez — copiarla y
   actualizarla en Claude. La de la **Terminal A** (keycloak) no cambia nunca
   (dominio fijo del Paso 1).

> **Si en vez de la demo querés probar tools sin login interactivo**, usá el
> bypass de token de Atina (HS256, ver AUTHENTICATION.md) — no necesita ngrok
> ni Keycloak en absoluto.

> **Alternativa si la demo puede esperar**: si para entonces ya tenés Digital
> Ocean levantado (Parte 4), usalo directo — dominios reales, sin túneles que
> mantener corriendo.

---

## Parte 3: Empaquetar y llevar a otra PC

La otra PC **no necesita** Java, Maven, ni el código fuente — solo Docker y la
carpeta `docker/`. La imagen del MCP Server se construye acá (esta máquina, que
tiene el JDK/Maven) y se sube a un registry; la otra PC solo la descarga.

### En esta máquina

**1. Build y push de la imagen** (necesitás una cuenta en Docker Hub, o
cualquier otro registry — `docker login` primero):

```bash
# Desde la raíz del repo
docker build -t <tu-usuario-dockerhub>/jde-mcp-server:dev .
docker push <tu-usuario-dockerhub>/jde-mcp-server:dev
```

> Esto arma la imagen para la arquitectura de **esta** máquina (ej. ARM si tu
> Mac es Apple Silicon). Si la otra PC es de otra arquitectura (Windows/Linux
> Intel, por ejemplo), usar `docker buildx build --platform linux/amd64 ...
> --push` como en la Parte 4 (Paso 1), apuntando a la plataforma de la PC
> destino.

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
> (Intel/AMD), aunque tu Mac sea Apple Silicon (ARM) o Intel. `docker buildx
> --platform linux/amd64` compila para esa arquitectura sin importar la tuya
> (usa emulación QEMU si tu Mac no es amd64 nativo -- el build tarda más que en
> local, pero el resultado corre bien en el droplet). Digital Ocean también
> ofrece droplets ARM ("Ampere") si en algún momento preferís evitar la
> emulación buildeando `--platform linux/arm64` nativo desde Apple Silicon,
> pero no es el default.

```bash
# Desde la raíz del repo (no docker/)
docker buildx create --name multiarch --use    # una sola vez por máquina

docker buildx build --platform linux/amd64 \
  -t <tu-usuario-o-registry>/jde-mcp-server:prod \
  --push .
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
