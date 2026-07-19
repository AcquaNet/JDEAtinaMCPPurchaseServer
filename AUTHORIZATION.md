# Validación del token y autorización (roles y scopes)

Este documento explica **dónde y cómo el MCP Server valida cada token**, y cómo funciona la **autorización por roles** (qué puede hacer cada usuario una vez autenticado). Complementa a [AUTHENTICATION.md](AUTHENTICATION.md) (cómo se obtienen los tokens y la sesión JDE) y a [TESTING.md](TESTING.md) (cómo probar todo).

> Distinción clave en dos preguntas:
> - **Autenticación**: ¿quién sos? → validación del token (parte 1)
> - **Autorización**: ¿qué podés hacer? → roles y scopes (partes 2 y 3)

---

## Parte 1 — Dónde se valida el token

Todo ocurre en `security/SecurityConfig.java`, **antes** de que el request toque cualquier código MCP:

```
POST /mcp con "Authorization: Bearer <token>"
   │
   ▼
① Filtros de Spring Security          /mcp exige autenticación
   ▼
② BearerTokenAuthenticationFilter     extrae el token del header
   ▼
③ jwtDecoder() — el ROUTER            peekIssuer(): lee el claim "iss"
   │                                  SIN validar (solo decide la rama)
   ├─ iss = realm de Keycloak ──►  ④ Rama KEYCLOAK
   ├─ iss = "Issue" ────────────►  ⑤ Rama ATINA
   │
   ▼ falla → ⑥ McpResourceMetadataEntryPoint
   │          401 + WWW-Authenticate: error, error_description, resource_metadata
   ▼ éxito → ⑦ JwtAuthenticationToken en el SecurityContext
              → recién acá el request entra al protocolo MCP
```

### Rama Keycloak (④): los 4 componentes validados

| # | Componente | Qué se chequea | Qué ataque frena |
|---|---|---|---|
| 1 | **Firma (RS256)** | El `kid` del header identifica la clave pública en el JWKS del realm; la firma debe verificar contra ella. Keycloak firma con su clave privada, que nunca sale del realm | Tokens inventados o modificados: cambiar un solo carácter del payload invalida la firma |
| 2 | **Expiración (`exp`/`nbf`)** | `exp` no puede estar en el pasado, `nbf` no en el futuro (tolerancia de reloj: 60s) | Replay de tokens viejos (los access token duran 5 min) |
| 3 | **Issuer (`iss`)** | Igualdad exacta con `spring.security.oauth2.resourceserver.jwt.issuer-uri` | Tokens de otro realm u otro Keycloak, aunque estén bien firmados |
| 4 | **Audiencia (`aud`)** | Debe contener `jde.mcp.security.expected-audience` (`atina-mcp-server`); el claim lo inyecta el mapper `jde-mcp-audience` del client | Tokens **válidos del mismo realm pero emitidos para otra aplicación** — sin esto, cualquier app del realm entraría acá |

Los cuatro corren juntos (`DelegatingOAuth2TokenValidator`): basta que uno falle para el 401. El decoder está envuelto en `SupplierJwtDecoder`, así que el discovery OIDC se difiere al primer token (el server arranca sin Keycloak disponible).

### Rama Atina (⑤): 3 componentes

| # | Componente | Detalle |
|---|---|---|
| 1 | **Firma (HS256)** | Simétrica, con el mismo secreto con que firma el microservicio (`ATINA_JWT_SECRET`). Vacío → la rama rechaza con mensaje explícito |
| 2 | **Timestamps** | Solo si los claims existen — los tokens de Atina no traen `exp`; la vigencia la controla el microservicio (claim `sessionId`) |
| 3 | **Issuer** | Debe ser `jde.atina.jwt.issuer` (`Issue`) |

Sin validación de audiencia: esos tokens no la tienen; el secreto compartido cumple ese rol.

---

## Parte 2 — Autorización por roles (implementado)

### El problema que resuelve

Autenticarse no debería alcanzar para **todo**. Consultar órdenes es lectura; **aprobar o rechazar** una orden es una decisión de negocio con impacto — debería requerir un permiso explícito.

### Por qué no se resuelve en SecurityConfig

Las reglas clásicas de Spring Security son por URL (`requestMatchers(...).hasRole(...)`), pero acá **todos los tools entran por el mismo endpoint** (`POST /mcp`, JSON-RPC): a nivel HTTP no se distingue "listar" de "aprobar". La autorización fina se chequea **a nivel del tool**.

### Cómo funciona

1. **El rol vive en Keycloak**: rol de realm `purchase-order-approve`, asignado por usuario (consola → realm `jde-integration` → Users → *usuario* → **Role mapping**).
2. **Viaja en el token**, en el claim `realm_access.roles`:
   ```json
   "realm_access": { "roles": ["purchase-order-approve", "offline_access", ...] }
   ```
3. **`security/RealmRoleGuard.java`** lee ese claim del JWT ya validado (del `SecurityContext`) y responde si el rol pedido está presente.
4. **`JdePurchaseApprovalTool`** lo consulta al inicio de `processPurchaseOrderInternal` (aprobar **y** rechazar — misma decisión de negocio). Sin el rol, el tool devuelve un mensaje claro al usuario (no un 500) y **nunca llega al backend**:
   > *"You are not authorized to approve purchase orders: your user does not have the 'purchase-order-approve' role in Keycloak…"*
5. El nombre del rol es configurable: `jde.mcp.security.approver-role` en `application.properties`.

### Política para tokens de Atina

`RealmRoleGuard` **autoriza siempre** a los tokens del microservicio de Atina. Razón: ese token ya es una sesión JDE emitida con un role JDE (claim `role`), y la autorización de qué puede hacer esa sesión la aplica **JDE en el backend** — duplicarla acá con roles de Keycloak mezclaría dos modelos de permisos.

### Matriz actual

| Tool | Requisito |
|---|---|
| `jde_login`, `jde_list_pending_purchase_orders`, `jde_get_purchase_order_detail`, `jde_get_customer_credit_info` | Token válido (cualquier usuario autenticado) |
| `jde_approve_purchase_order`, `jde_reject_purchase_order` | Token válido **+ rol `purchase-order-approve`** (si el token es de Keycloak) |

### Cómo extender el patrón (la "base")

Para proteger otro tool con otro rol:

1. Crear el rol de realm en Keycloak y asignarlo a los usuarios que corresponda.
2. Agregar la property (ej. `jde.mcp.security.credit-role=customer-credit-read`).
3. En el tool: inyectar `RealmRoleGuard` + la property, y chequear al inicio:
   ```java
   if (!roleGuard.hasRealmRole(creditRole)) {
       return "You are not authorized to ...";
   }
   ```

> ⚠️ El usuario debe **volver a loguearse** después de recibir un rol nuevo: el rol viaja dentro del token, y el token viejo no lo tiene.

---

## Parte 3 — Scopes: qué son y cuándo agregarlos (no implementado)

### Roles vs. scopes — la diferencia que importa

| | **Rol** | **Scope** |
|---|---|---|
| Describe | Qué **es/puede el usuario** (RBAC) | Qué **permiso pidió la aplicación cliente** para actuar en nombre del usuario |
| Lo otorga | El administrador, al usuario, de forma permanente | El usuario (o la config del client), al client, por sesión |
| Claim | `realm_access.roles` | `scope` |
| Ejemplo | "Javier es aprobador de compras" | "Postman pidió permiso de lectura de compras" |

Hoy el token trae `scope: "email profile"` (scopes OIDC estándar de identidad) y **ningún tool los exige** — por eso el metadata RFC 9728 no publica `scopes_supported`, y eso es correcto.

### Cuándo tendría sentido agregar scopes

Cuando haya **múltiples clientes con distintos niveles de acceso a la API**. Ejemplo: un dashboard de solo-lectura no debería poder invocar aprobaciones *aunque el usuario logueado tenga el rol* — el scope limita al **cliente**, el rol limita al **usuario**. Son complementarios: aprobar exigiría `scope=jde:purchase:approve` (el client puede) **y** rol `purchase-order-approve` (el usuario puede).

### Receta cuando llegue ese momento

1. En Keycloak: crear *client scopes* (ej. `jde:purchase:read`, `jde:purchase:approve`, `jde:credit:read`) y asignarlos a cada client como default u optional según su nivel.
2. En el MCP Server: exigirlos por tool (mismo patrón que `RealmRoleGuard`, leyendo el claim `scope`) o vía `hasAuthority("SCOPE_jde:purchase:approve")` si se mapean a authorities.
3. Publicar `scopes_supported` en `ProtectedResourceMetadataController` para que los clientes sepan qué pedir.

---

## Referencias de código

| Qué | Dónde |
|---|---|
| Pipeline de validación (router + ramas) | `security/SecurityConfig.java` |
| 401 con motivo + metadata RFC 9728 | `security/McpResourceMetadataEntryPoint.java` |
| Guard de roles de realm | `security/RealmRoleGuard.java` |
| Chequeo en aprobar/rechazar | `purchase/tools/JdePurchaseApprovalTool.java` (`processPurchaseOrderInternal`) |
| Rol requerido (configurable) | `application.properties` → `jde.mcp.security.approver-role` |
