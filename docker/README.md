# Docker: Keycloak + OpenBao + MCP Server + Caddy

Stack de infraestructura para el JDE MCP Server: Keycloak (OAuth2), OpenBao (vault de
credenciales JDE), el propio MCP Server, y Caddy (reverse proxy + HTTPS automático en
producción).

> **Guía paso a paso de despliegue** (local con Docker y en Digital Ocean): ver
> **[../DEPLOYMENT.md](../DEPLOYMENT.md)**. Este archivo es solo referencia rápida de
> qué hay en esta carpeta.

## Contenido de la carpeta

| Archivo/carpeta | Qué es |
|---|---|
| `docker-compose.yml` | Los 3 profiles (`dev`, `stage`, `prod`) — ver comentario de cabecera del archivo |
| `.env` | Variables de `dev`, committeado (placeholders, no secretos reales) |
| `.env.stage.example` / `.env.prod.example` | Plantillas committeadas — copiar a `.env.stage` / `.env.prod` (gitignored) y completar |
| `deploy.env.example` | Plantilla para `deploy.env` (gitignored) — datos SSH del droplet |
| `caddy/Caddyfile.prod` | Config de Caddy para producción (HTTPS automático, sin `tls` explícito) |
| `keycloak/realm-export.json` | Realm `jde-integration` exportado (config + usuarios) — se importa automático al levantar un Keycloak nuevo |
| `keycloak/export-realm.sh` | Re-exporta el realm después de cambios manuales en la consola admin |
| `scripts/deploy.sh` | Redespliega `mcp-server` en el droplet por SSH (usa `deploy.env`) |
| `scripts/package.sh` | Arma un ZIP portable (sin secretos, sin código fuente) para llevar el stack a otra PC |

## Ambientes (Docker Compose profiles)

Un profile por ambiente, con el mismo nombre que `spring.profiles.active` de la app:

| Profile | Servicios | Uso |
|---|---|---|
| `dev` | keycloak-db, keycloak, openbao, mcp-server | Desarrollo local (ver [DEPLOYMENT.md](../DEPLOYMENT.md#parte-1-preparar-tu-máquina)) |
| `stage` | keycloak-db, keycloak, openbao, mcp-server | Sin Caddy/dominio público todavía, atado a `127.0.0.1` |
| `prod` | keycloak-db, keycloak, openbao, mcp-server, caddy | Digital Ocean, HTTPS público (ver [DEPLOYMENT.md](../DEPLOYMENT.md#parte-4-digital-ocean-prod)) |

```bash
docker compose --profile dev up -d
docker compose --profile stage --env-file .env.stage up -d
docker compose --profile prod  --env-file .env.prod  up -d
```

## Notas rápidas

- **`MCP_KEYCLOAK_ISSUER_URI` debe ser idéntico a `KC_HOSTNAME`** (Keycloak firma el
  claim `iss` con su propio `KC_HOSTNAME`) — si no coinciden, se rechazan todos los
  tokens. Detalle en [DEPLOYMENT.md](../DEPLOYMENT.md).
- El login OAuth completo por browser no funciona de entrada en `dev`/`stage`
  (sin Caddy, todo en `localhost`) — para demos con login real, usar ngrok:
  [DEPLOYMENT.md, Parte 2](../DEPLOYMENT.md#parte-2-demo-con-ngrok-login-oauth-real).
- `docker/.env.stage`, `docker/.env.prod` y `docker/deploy.env` están gitignored
  (secretos reales) — solo se commitean los `.example`.
