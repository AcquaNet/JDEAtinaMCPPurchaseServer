# Keycloak para JDE MCP Server — Caso 1 (Claude Desktop)

## 1. Levantar Keycloak

```bash
cp .env.example .env
# editar .env con passwords reales
docker compose up -d
```

Consola admin: http://localhost:8180 (usuario/clave de `.env`)

## 2. Crear el realm y el client

Copiar `setup-realm-case1.sh` dentro del contenedor y ejecutarlo, o correrlo
desde una máquina con `kcadm.sh` disponible apuntando a `http://localhost:8180`.

```bash
docker cp setup-realm-case1.sh keycloak:/tmp/setup-realm-case1.sh
docker exec -e KC_URL=http://localhost:8080 -it keycloak bash /tmp/setup-realm-case1.sh
```

Esto crea:
- Realm `jde-integration`
- Client público `claude-desktop-mcp` con Authorization Code + PKCE (S256)
- Protocol mapper que fija la audiencia del access token al propio client
  (para que ese token no sirva contra otro recurso — RFC 8707)

## 3. Lo que falta del lado del JDE MCP Server (Spring Boot)

El MCP Server tiene que pasar de "recibir un Bearer fijo por config" a
comportarse como **OAuth Resource Server**:

1. Exponer `/.well-known/oauth-protected-resource` (metadata de recurso protegido)
   apuntando a `http://localhost:8180/realms/jde-integration` como authorization server.
2. Validar el JWT entrante contra el JWKS de Keycloak
   (`http://localhost:8180/realms/jde-integration/protocol/openid-connect/certs`),
   chequeando `iss`, `exp` y `aud=claude-desktop-mcp`.
3. Extraer el `sub` (o un claim custom) del token validado y pasarlo al
   Identity bridge en lugar de leer el JWT de Atina desde la config estática.

## 4. Lo que falta construir: Identity bridge

Todavía no existe. Tiene que:
- Recibir el `sub` de Keycloak.
- Resolver la credencial JDE asociada (tabla `keycloak_sub -> jde_user/environment/role`).
- Llamar a Atina (`Login` o `ProcessToken`) con esa credencial.
- Cachear el JWT de Atina resultante mientras esté vigente, para no loguear en
  JDE en cada request.

## Próximos pasos sugeridos

- Validar este flujo end-to-end con Claude Desktop antes de tocar el caso de WhatsApp.
- Migrar `start-dev` a `start` + hostname real cuando se mueva a la VM de Azure.
- Definir dónde vive el vault de credenciales JDE (no en Keycloak, no en el MCP Server).
