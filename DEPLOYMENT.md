# Guía de Despliegue — JDE MCP Server

Guía paso a paso para desplegar el stack (Keycloak + OpenBao + MCP Server, y en
producción también Caddy) tanto en **Docker local** (dev/stage) como en
**Digital Ocean** (prod). Para referencia rápida de qué archivos hay en `docker/`
y qué hace cada uno, ver [docker/README.md](docker/README.md); esta guía es el
recorrido completo de principio a fin.

---

## Índice

- [Arquitectura del despliegue](#arquitectura-del-despliegue)
- [Parte 1: Docker local (dev y stage)](#parte-1-docker-local-dev-y-stage)
- [Demo local con ngrok](#demo-local-con-ngrok)
- [Parte 2: Digital Ocean (prod)](#parte-2-digital-ocean-prod)
- [Keycloak: exportar/importar el realm](#keycloak-exportarimportar-el-realm)
- [Troubleshooting](#troubleshooting)

---

## Arquitectura del despliegue

```
                         Internet
                            │
                 (solo prod: 80/443)
                            ▼
                        ┌────────┐
                        │ Caddy  │  <- HTTPS automático (Let's Encrypt)
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

En **dev/stage** no hay Caddy: cada servicio se publica directo por puerto,
atado a `127.0.0.1` (no accesible desde otras máquinas). En **prod**, Caddy es
el único servicio con puertos públicos (80/443); todo lo demás queda interno.

Los tres ambientes (`dev` | `stage` | `prod`) son **Docker Compose profiles**
del mismo `docker/docker-compose.yml`, con el mismo nombre que
`spring.profiles.active` de la app.

---

## Parte 1: Docker local (dev y stage)

### Prerequisitos

- Docker Desktop (o Docker Engine + Compose plugin) instalado y corriendo.
- Java 25 + Maven solo si además vas a correr el MCP Server desde el IDE
  (flujo normal de día a día en dev).

### 1. Levantar la infraestructura (Keycloak + OpenBao)

**Este es el flujo normal de día a día**: correr el MCP Server **desde el
IDE/IntelliJ** (`./mvnw spring-boot:run`, o el botón Run — perfil `dev` activo
por defecto), apuntando a Keycloak/OpenBao levantados por Docker. Para eso
levantá solo esos dos servicios, **sin** el contenedor `mcp-server`:

```bash
cd docker
docker compose --profile dev up -d keycloak-db keycloak openbao
```

> Si corrés `docker compose --profile dev up -d` **sin** listar servicios,
> también arranca el contenedor `mcp-server`, que publica el puerto 8080 igual
> que IntelliJ — van a pelear por el mismo puerto. Si ya lo levantaste sin
> querer: `docker compose --profile dev stop mcp-server`.

Verificar que levantó todo:

```bash
docker compose --profile dev ps
curl -s http://localhost:8180/realms/jde-integration | jq .realm     # Keycloak
curl -s http://localhost:8200/v1/sys/health | jq .initialized        # OpenBao
```

Ahora sí, corré el MCP Server desde IntelliJ (o `./mvnw spring-boot:run`) —
funciona exactamente igual que antes de este cambio de Docker, nada nuevo que
aprender ahí.

### 2. Probar la imagen Docker del MCP Server (opcional)

Solo si además querés validar que la **imagen Docker** en sí funciona (por
ejemplo antes de un deploy). **Pará el MCP Server del IDE primero** (mismo
puerto 8080):

```bash
# Build local de la imagen (usa el Dockerfile de la raíz del repo)
docker compose --profile dev build mcp-server
docker compose --profile dev up -d mcp-server
docker compose --profile dev logs -f mcp-server
curl -s http://localhost:8080/.well-known/oauth-protected-resource
```

> **Limitación conocida**: con el `mcp-server` containerizado en `dev`, el
> login OAuth completo por browser (Claude.ai/Desktop) **no** va a funcionar tal
> cual — Keycloak firma el token con `KC_HOSTNAME=http://localhost:8180`, pero
> desde adentro del contenedor `mcp-server` esa URL no llega a Keycloak (ahí
> "localhost" es el propio contenedor). Para probar tools sin el login
> interactivo, usá el bypass de token de Atina (HS256, ver AUTHENTICATION.md).
> Para una demo con login OAuth real sin desplegar a producción todavía, ver
> [Demo local con ngrok](#demo-local-con-ngrok) más abajo.

### 3. Ambiente `stage` — ¿en qué se diferencia de `dev`?

La única diferencia real hoy es **a qué backend de JDE apunta**, no la
infraestructura Docker en sí (misma Keycloak/OpenBao, sin Caddy en ninguno de
los dos):

- **`dev`**: `application-dev.properties` tiene *hardcodeado* `localhost` para
  Mulesoft, Gateway de Atina, Keycloak y OpenBao (con fallback). Pensado para
  tu propia máquina, donde vos sabés qué corre en esos puertos.
- **`stage`**: `application-stage.properties` **exige** (sin fallback) las URLs
  de Mulesoft/Gateway/Keycloak/OpenBao vía variables de entorno
  (`JDE_MULESOFT_BASE_URL`, etc.) — pensado para apuntar a un ambiente de
  staging *real y compartido* de JDE/Mulesoft (no tu laptop), típicamente
  usado por todo un equipo antes de ir a producción.

**Si todavía no existe ese ambiente de staging de JDE**, `stage` no te sirve de
nada por ahora — usá solo `dev` (tu máquina) y `prod` (Digital Ocean) e ignorá
`stage` hasta que haga falta.

Cuando exista, para usarlo:

```bash
cd docker
cp .env.stage.example .env.stage   # completar con los hosts reales de stage
docker compose --profile stage --env-file .env.stage up -d
```

### 4. Apagar / limpiar

```bash
docker compose --profile dev down          # para los contenedores, conserva los volúmenes
docker compose --profile dev down -v       # además borra volúmenes (¡pierde datos de Keycloak/OpenBao/H2!)
```

### Demo local con ngrok

Resuelve la limitación de arriba exponiendo **Keycloak y el MCP Server** por
HTTPS público, sin tocar Digital Ocean todavía. Sirve tanto si el MCP Server
corre en el contenedor como desde el IDE — la clave es que Keycloak deje de
firmar los tokens como `localhost` (que ni Claude.ai ni un contenedor pueden
alcanzar) y pase a firmarlos con una URL pública que todos puedan resolver.

1. **Dos túneles ngrok** (un solo `ngrok` puede levantar varios si tenés cuenta
   gratuita con dominios/túneles habilitados; si no, dos terminales):
   ```bash
   ngrok http 8180     # Keycloak -> anotá la URL pública, ej. https://abcd1234.ngrok-free.app
   ngrok http 8080     # MCP Server -> anotá la otra URL pública
   ```
   (Con cuenta gratuita de ngrok podés reservar un dominio estático fijo para no
   tener que cambiar la URL cada vez que reiniciás el túnel.)

2. **Recrear Keycloak con `KC_HOSTNAME` apuntando al túnel**:
   ```bash
   KC_HOSTNAME=https://abcd1234.ngrok-free.app \
   docker compose --profile dev up -d --force-recreate keycloak
   ```

3. **El MCP Server tiene que validar contra esa misma URL** (tiene que
   coincidir exacto con `KC_HOSTNAME`, ver nota más abajo sobre por qué):
   - Si corre desde IntelliJ: agregar como variable de entorno del run
     configuration `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://abcd1234.ngrok-free.app/realms/jde-integration`.
   - Si corre containerizado: `MCP_KEYCLOAK_ISSUER_URI=https://abcd1234.ngrok-free.app/realms/jde-integration docker compose --profile dev up -d --force-recreate mcp-server`.

4. **Apuntar Claude Desktop/Claude.ai** a la URL del segundo túnel:
   `https://<otra-url>.ngrok-free.app/mcp`.

5. Si el login falla con error de `redirect_uri` inválido, agregar la URL de
   callback exacta que informa el error al client `atina-mcp-server` en la
   consola admin de Keycloak (ahora en `https://abcd1234.ngrok-free.app/admin`).

> **Alternativa más simple si la demo puede esperar**: si para cuando sea la
> demo ya tenés Digital Ocean levantado (Parte 2), usar directamente ese
> ambiente evita todo esto — los dominios reales ya resuelven igual para
> cualquier cliente, sin túneles que reconfigurar.

---

## Parte 2: Digital Ocean (prod)

Dominio de este despliegue: **`jdemcp-atina-connection.com`** (MCP Server) y
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

El droplet no necesita el código fuente, solo la carpeta `docker/` — la imagen
se buildea en otra máquina (esta) y se publica en un registry.

> **Mac → Linux**: los droplets "estándar" de Digital Ocean son `linux/amd64`
> (Intel/AMD), aunque tu Mac sea Apple Silicon (ARM) o Intel. `docker buildx
> --platform linux/amd64` compila para esa arquitectura sin importar la tuya
> (usa emulación QEMU si tu Mac no es amd64 nativo -- el build tarda más que en
> local, pero el resultado corre bien en el droplet). Si en algún momento
> preferís evitar la emulación, Digital Ocean también ofrece droplets ARM
> ("Ampere") -- ahí sí buildearías `--platform linux/arm64` nativo y rápido
> desde un Mac Apple Silicon, pero es una elección explícita del droplet, no el
> default.

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

> ⚠️ **`MCP_KEYCLOAK_ISSUER_URI` tiene que ser idéntico a `KC_HOSTNAME`.**
> Keycloak firma el claim `iss` de cada token con su propio `KC_HOSTNAME`; si el
> MCP Server espera un issuer distinto, rechaza **todos** los tokens (401 en
> todo). Es el error más fácil de cometer acá — revisar dos veces si algo
> devuelve 401 inesperadamente.

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
| Caddy no obtiene certificado | DNS no resuelve al droplet, o puertos 80/443 no accesibles desde internet | `dig +short jdemcp-atina-connection.com`, `curl http://jdemcp-atina-connection.com` desde otra máquina, `docker compose ... logs caddy` |
| Todos los tokens de Keycloak son rechazados (401) | `MCP_KEYCLOAK_ISSUER_URI` no coincide con `KC_HOSTNAME` | Revisar que ambos sean la misma URL pública HTTPS en `.env.prod` |
| Claude.ai no puede completar el login | Redirect URI no autorizado en el client de Keycloak | Ver Paso 5, checklist |
| `jde.vault.addr`/`jde.vault.token` fallan | `BAO_TOKEN` no seteado, o token de OpenBao expirado/inválido | `docker compose ... logs openbao`; por SSH tunnel: `curl http://127.0.0.1:8200/v1/sys/health` |
| Login OAuth no funciona en `dev`/`stage` containerizado | Limitación conocida (ver Parte 1, paso 1) | Usar el IDE para probar el login real |

### Seguridad

- Solo Caddy publica puertos al mundo (80/443) en `prod`. Keycloak, OpenBao y
  `mcp-server` quedan atados a `127.0.0.1` del droplet — para administrarlos
  desde afuera, usar un túnel SSH: `ssh -L 8180:127.0.0.1:8180 root@<IP-DROPLET>`.
- Usar el `OPENBAO_ROOT_TOKEN` solo en dev. En stage/prod, crear una policy de
  OpenBao de solo lectura sobre `secret/data/jde/*` y usar un token acotado a
  esa policy como `BAO_TOKEN`.
- `docker/.env.stage`, `docker/.env.prod` y `docker/deploy.env` están
  gitignored (contienen secretos reales) — solo se commitean los `.example`.
