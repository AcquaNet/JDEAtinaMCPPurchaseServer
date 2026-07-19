# Testing del MCP Server con OAuth 2.1

Guía práctica para probar el JDE MCP Server autenticándose contra Keycloak, con cualquiera de estas herramientas: **Postman**, **MCP Inspector** o **curl**. Complementa a [AUTHENTICATION.md](AUTHENTICATION.md) (que explica *cómo funciona* la autenticación; acá explicamos *cómo probarla*).

---

## 1. Antes de empezar: qué tiene que estar corriendo

| Servicio | Puerto | Verificación rápida |
|---|---|---|
| Keycloak (realm `jde-integration`) | 8180 | `curl -s http://localhost:8180/realms/jde-integration \| jq .realm` |
| MCP Server (esta app) | 8080 | `curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/mcp` → debe dar `401` (sin token es lo correcto) |
| Mulesoft / Atina API | 8085 | `curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/api/v1/login` → `405` (es POST-only, pero responde) |
| OpenBao (vault) | 8200 | `curl -s http://localhost:8200/v1/sys/health \| jq .initialized` |

También necesitás un **usuario del realm** (ej.: `jgodino`) — no sirve el admin de Keycloak (vive en el realm `master`) ni las credenciales JDE (esas son de otra capa).

## 2. Conceptos en un minuto

- **OAuth 2.1** = Authorization Code + PKCE (S256). El usuario se loguea **en el browser, en la pantalla de Keycloak** — la herramienta de testing nunca ve la contraseña. El client `atina-mcp-server` tiene PKCE S256 **forzado**: un intento de authorization code sin PKCE es rechazado.
- El **access token dura 5 minutos**. Con el flujo OAuth 2.1 también recibís un *refresh token*, así la herramienta renueva sola. Con tokens copiados a mano, a los 5 minutos volvés a ver 401.
- El `password` grant (mandar usuario y contraseña directo al token endpoint) **existe solo como comodidad de desarrollo** — OAuth 2.1 lo eliminó del estándar y en producción se deshabilita (ver Production Checklist del README). Lo usamos únicamente en los curls.
- Con el **Identity Bridge** activo, no hace falta llamar `jde_login`: tu identidad Keycloak se resuelve automáticamente a una sesión JDE.

## 3. La secuencia MCP (igual para todas las herramientas)

Todo request va a `POST http://localhost:8080/mcp` con estos headers:

| Header | Valor | Obligatorio |
|---|---|---|
| `Authorization` | `Bearer <access-token>` | ✅ Siempre |
| `Content-Type` | `application/json` | ✅ Siempre |
| `Accept` | `application/json, text/event-stream` | ✅ Siempre — **sin él, el server tira una excepción del transporte** |
| `Mcp-Session-Id` | el que devuelve el initialize | ✅ En todos menos el initialize |

La conversación siempre es:

```
1) initialize            → la respuesta trae el header Mcp-Session-Id: copiálo
2) notifications/initialized   (SIN "id" — es una notificación, no un request)
3) tools/list            → ver qué tools hay
4) tools/call            → invocar un tool
```

**Bodies exactos:**

```json
// 1) initialize
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}

// 2) initialized — sin id, sin params
{"jsonrpc":"2.0","method":"notifications/initialized"}

// 3) tools/list
{"jsonrpc":"2.0","id":2,"method":"tools/list"}

// 4) tools/call — ejemplo: órdenes pendientes
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"jde_list_pending_purchase_orders","arguments":{"limit":5}}}

// 4b) tools/call — ejemplo: detalle de una orden (los 4 identificadores son obligatorios;
//     usar EXACTAMENTE los valores que devolvió el listado — el número va sin comillas)
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"jde_get_purchase_order_detail","arguments":{"documentOrderTypeCode":"OP","documentOrderInvoiceNumber":5082,"documentCompanyKeyOrderNo":"00001","documentSuffix":"000"}}}
```

Las respuestas vienen en formato SSE: el JSON está en la línea que empieza con `data:`.

---

## 4. Opción A — Postman con OAuth 2.1 (recomendado)

### Prerequisito (ya aplicado en este entorno)

Los callbacks de Postman deben estar en los **Valid redirect URIs** del client. Verificalo en: consola Keycloak → realm `jde-integration` → Clients → `atina-mcp-server` → Settings → *Access settings*:

```
https://oauth.pstmn.io/v1/callback
https://oauth.pstmn.io/v1/browser-callback
```

Sin esto, el flujo muere con `invalid_redirect_uri` antes de pedirte credenciales.

### Configuración (una sola vez, a nivel colección)

Colección → **Authorization** → Type **OAuth 2.0** → *Configure New Token*:

| Campo | Valor |
|---|---|
| Grant type | **Authorization Code (With PKCE)** |
| Authorize using browser | ✅ marcado |
| Auth URL | `http://localhost:8180/realms/jde-integration/protocol/openid-connect/auth` |
| Access Token URL | `http://localhost:8180/realms/jde-integration/protocol/openid-connect/token` |
| Client ID | `atina-mcp-server` |
| Client Secret | *(vacío — client público)* |
| Code Challenge Method | **SHA-256** |
| Scope | `openid profile email` |
| Client Authentication | Send client credentials in body |

Y arriba: *Add auth data to* = **Request Headers**, *Header Prefix* = **Bearer**. Activá **Auto-refresh token** para olvidarte de los 5 minutos.

### Uso

1. **Get New Access Token** → se abre el browser con el login de Keycloak → ingresá tu usuario del realm.
2. **Use Token**.
3. Creá los requests de la sección 3 con Authorization = *Inherit auth from parent*. El `Mcp-Session-Id` del initialize lo ves en la pestaña **Headers** de la respuesta; pegalo como header en los siguientes.

> Keycloak mantiene sesión SSO en el browser: la segunda vez puede no pedirte credenciales. Para cambiar de usuario: cerrá sesión en `http://localhost:8180/realms/jde-integration/account` o usá ventana de incógnito.

### Alternativa: request MCP nativo

Postman v11+ tiene **New → MCP Request**: tipo HTTP, URL `http://localhost:8080/mcp`, y el header `Authorization` con el token. Postman maneja la sesión MCP solo y te muestra los tools como formularios.

---

## 5. Opción B — MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

En la UI: Transport **Streamable HTTP**, URL `http://localhost:8080/mcp`.

**Con OAuth (recomendado):** en Authentication → OAuth 2.0 Flow, con Client ID `atina-mcp-server` → el Inspector descubre Keycloak solo (vía el metadata RFC 9728 que expone el server en `/.well-known/oauth-protected-resource`) y abre el browser.

**Con token manual:** pegá el token en el campo **Bearer Token** (¡solo el token, sin la palabra "Bearer" — el campo la agrega!). Generalo con el curl de la sección 6. Recordá los 5 minutos.

> El Inspector no envía `Mcp-Session-Id`; el server usa tu IP como sesión — todas las pestañas comparten sesión. Es esperado en desarrollo.

---

## 6. Opción C — curl

**Obtener token** (⚠️ usa el `password` grant — solo desarrollo; `jq -r` imprime el token crudo, sin comillas ni coma):

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/jde-integration/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=atina-mcp-server" \
  -d "username=<usuario-realm>" \
  -d "password=<clave>" | jq -r .access_token)
```

**Secuencia completa:**

```bash
# 1) initialize (el -i muestra los headers: buscá Mcp-Session-Id)
curl -i -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'

SID=<el-Mcp-Session-Id-de-arriba>

# 2) initialized
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: $SID" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3) invocar un tool
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: $SID" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"jde_list_pending_purchase_orders","arguments":{"limit":5}}}'
```

### ¿Y Authorization Code + PKCE con curl?

**No existe "un curl" para este flujo, y es a propósito**: tiene un paso humano en el browser (el login en Keycloak) para que la contraseña nunca pase por la herramienta. Postman/mcp-remote/Inspector automatizan estos pasos; a mano se ve así:

```bash
# Paso 0: generar el par PKCE (secreto efímero + su hash)
CODE_VERIFIER=$(openssl rand -base64 60 | tr -d '=+/\n' | cut -c1-64)
CODE_CHALLENGE=$(printf '%s' "$CODE_VERIFIER" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
```

**Paso 1 (browser, sin curl):** abrir esta URL, loguearse en Keycloak, y copiar el `code=` de la barra de direcciones tras el redirect (la página de `localhost:8123` no carga — es esperado; el code está en la URL y dura ~1 minuto):

```
http://localhost:8180/realms/jde-integration/protocol/openid-connect/auth?client_id=atina-mcp-server&response_type=code&redirect_uri=http://localhost:8123/callback&scope=openid%20profile%20email&code_challenge=<CHALLENGE>&code_challenge_method=S256
```

```bash
# Paso 2: canjear el code (redirect_uri debe ser IDÉNTICO al del paso 1)
curl -s -X POST http://localhost:8180/realms/jde-integration/protocol/openid-connect/token \
  -d "grant_type=authorization_code" -d "client_id=atina-mcp-server" \
  -d "code=<CODE>" -d "redirect_uri=http://localhost:8123/callback" \
  -d "code_verifier=$CODE_VERIFIER" | jq
# → devuelve access_token Y refresh_token

# Paso 3: renovar sin browser cuando expire (cada 5 min)
curl -s -X POST http://localhost:8180/realms/jde-integration/protocol/openid-connect/token \
  -d "grant_type=refresh_token" -d "client_id=atina-mcp-server" \
  -d "refresh_token=<REFRESH_TOKEN>" | jq -r .access_token
```

Para el día a día en terminal usá el `password` grant de arriba; este desglose sirve para entender qué hace Postman con el botón *Get New Access Token*.

---

## 7. ¿Cómo verifico que mi flujo es realmente OAuth 2.1?

Sutileza importante: **"OAuth 2.1" no es un atributo del token, es un atributo del camino por el que se obtuvo**. Un JWT sacado con el `password` grant es idéntico en formato a uno del flujo con browser — lo que hace "2.1" al segundo es cómo se emitió, y eso se verifica contra el servidor:

| Requisito OAuth 2.1 | Cómo se cumple acá | Cómo comprobarlo |
|---|---|---|
| Authorization Code con **PKCE obligatorio** | El client tiene forzado `pkce.code.challenge.method=S256` | Test empírico de abajo: sin `code_challenge` Keycloak rechaza |
| La contraseña solo se tipea en el servidor de identidad | El login ocurre en la pantalla de Keycloak, en un browser | La herramienta (Postman/curl) nunca recibe la clave, solo el code y el token |
| Client público sin secret | `Client Secret` vacío | Robar el code no sirve sin el `code_verifier` efímero |
| Redirects solo a lista blanca | Valid redirect URIs del client | Un redirect no registrado da `invalid_redirect_uri` (queda logueado en Keycloak) |
| Sin implicit flow ni password grant | Implicit nunca estuvo habilitado; password es dev-only | Ver checklist de producción |

**Test empírico** — probá vos mismo que PKCE es obligatorio:

```bash
# SIN code_challenge → Keycloak rechaza (esto es lo que exige 2.1):
curl -s -o /dev/null -w "%{redirect_url}\n" \
  "http://localhost:8180/realms/jde-integration/protocol/openid-connect/auth?client_id=atina-mcp-server&response_type=code&redirect_uri=http://localhost:8123/callback&scope=openid"
# → ...error=invalid_request&error_description=Missing+parameter:+code_challenge_method

# CON code_challenge S256 → HTTP 200 (muestra la pantalla de login):
curl -s -o /dev/null -w "HTTP %{http_code}\n" \
  "http://localhost:8180/realms/jde-integration/protocol/openid-connect/auth?client_id=atina-mcp-server&response_type=code&redirect_uri=http://localhost:8123/callback&scope=openid&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256"
```

### Con el token en la mano: cerrar el círculo

1. **Correr la secuencia MCP completa** (sección 3) con ese token: si `jde_list_pending_purchase_orders` responde, quedó validada la cadena entera — *login browser (2.1) → token → MCP server (audiencia) → Identity Bridge → vault → JDE* — sin tipear una sola credencial JDE.
2. **Activar Auto-refresh Token** en Postman (se habilita recién cuando existe un token con refresh).
3. **Prueba de pureza (opcional)**: deshabilitar *Direct Access Grants* en el client y verificar que Postman, Claude Desktop y el Inspector siguen funcionando — lo único que muere son los curls con `password`. Eso demuestra que nada del ecosistema depende del grant que 2.1 eliminó. En dev se puede volver a habilitar después.

---

## 8. Checklist al pasar a producción

En desarrollo esta guía usa deliberadamente atajos que **no deben llegar a producción**. La lista completa de infraestructura (Keycloak en modo `start`, OpenBao con storage persistente, HTTPS, secretos, etc.) está en la [Production Checklist del README](README.md#production-checklist); lo que sigue es la parte que afecta directamente a lo descripto en esta guía:

- [ ] **Deshabilitar Direct Access Grants** en el client `atina-mcp-server` (consola → Clients → Settings → Capability config). El `password` grant fue eliminado de OAuth 2.1; en prod, todos los tokens deben salir del flujo con browser. Consecuencia esperada: los curls de la sección 6 dejan de funcionar — es la señal de que quedó bien.
- [ ] **Sacar los redirect URIs de Postman** (`https://oauth.pstmn.io/v1/*`) de los Valid redirect URIs del client — eran solo para testear. Dejar únicamente los redirects reales de los clientes productivos; cuanto más corta la lista, menor superficie de ataque.
- [ ] **Revisar los redirects `http://localhost:*`**: válidos para loopback de `mcp-remote`/Claude Desktop; si en prod ningún cliente corre en la máquina del usuario, sacarlos también.
- [ ] **Verificar que PKCE S256 siga forzado** en el client (test empírico de la sección 7) después de cualquier cambio en Keycloak.
- [ ] **Bajar el Access Token Lifespan si se estiró en dev** (Realm settings → Tokens): si lo subiste para probar cómodo, en prod volvé a un valor corto (5-15 min) — el refresh token hace el trabajo.
- [ ] **Repetir la "prueba de pureza"** de la sección 7 como test de aceptación: con password grant apagado y redirects limpios, todo el ecosistema real debe seguir funcionando.

---

## 9. Diagnóstico de errores

El server incluye el **motivo del 401 en el header `WWW-Authenticate`** de la respuesta — miralo siempre primero (en Postman: Console; en curl: `-i`):

```
WWW-Authenticate: Bearer error="invalid_token", error_description="Jwt expired at ...", resource_metadata="..."
```

| Síntoma | Causa | Solución |
|---|---|---|
| 401 + `Jwt expired at ...` | Pasaron los 5 minutos | Regenerar token (o usar OAuth con auto-refresh) |
| 401 + `Malformed payload` | Token pegado con basura (comillas/coma del JSON, "Bearer" duplicado, truncado) | Copiar con `jq -r`; en el campo Bearer Token del Inspector va el token solo |
| 401 + `Audiencia no coincide...` | Token emitido para otro client del realm | Usar `client_id=atina-mcp-server` |
| 401 sin descripción | No llegó ningún token | Revisar que el header `Authorization` esté presente y herede bien en Postman |
| Respuesta con stacktrace de `WebMvcStreamableServerTransportProvider` | Falta el header `Accept: application/json, text/event-stream` | Agregarlo a **todos** los requests |
| El server "no reconoce" la sesión / errores raros tras initialize | Falta `Mcp-Session-Id`, o mandaste `notifications/initialized` con `id` | Header en todos los requests post-initialize; la notificación va sin `id` |
| `invalid_client` al pedir token | Client ID incorrecto (¿quedó uno viejo?) | El client es `atina-mcp-server` |
| `invalid_redirect_uri` en el browser | El callback de la herramienta no está en Valid redirect URIs | Agregarlo al client (sección 4) |
| Tool responde error y el log del MCP muestra `408 ... from GET http://localhost:8085/...` | Timeout **dentro de Mulesoft** esperando a JDE (el MCP espera hasta 10 min; ese 408 viene del backend) | Ajustar el timeout en la app Mule / reintentar con kernels calientes |
| "Tu usuario no tiene un usuario JDE asociado" | El `sub` de tu usuario Keycloak no está en `identity_mapping` | Alta con `scripts/seed-identity-dev.sh`, o usar `jde_login` manual |

---

## 10. Referencias

- [AUTHENTICATION.md](AUTHENTICATION.md) — cómo funciona la autenticación por dentro (las dos capas, el Identity Bridge, los tokens de Atina).
- [README.md](README.md) — configuración general, tools disponibles, OpenBao y Production Checklist (incluye deshabilitar el `password` grant y limpiar los redirect URIs de Postman en producción).
