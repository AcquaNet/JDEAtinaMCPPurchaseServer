# Análisis: filtrar `tools/list` según el rol del usuario

> **Estado: análisis para decidir más adelante — nada de esto está
> implementado.** Guardado como referencia para retomar la decisión cuando
> haga falta, sin tener que rehacer la investigación.

## Contexto: qué existe hoy

Ningún tool se oculta hoy según el rol. `tools/list` devuelve **siempre los
mismos tools a cualquier usuario autenticado**, sin importar sus roles de
Keycloak. La autorización por rol que sí existe (`RealmRoleGuard.hasRealmRole(...)`,
`src/main/java/com/atina/jdeMCPServer/security/RealmRoleGuard.java`) se
chequea **adentro del tool, en el momento de `tools/call`**, no al listar:

```java
// JdePurchaseApprovalTool.java:421 (aprobar/rechazar OC)
if (!roleGuard.hasRealmRole(approverRole)) {
    return "You are not authorized to approve purchase orders: your user does not have the '...' role...";
}
```

Un usuario sin el rol `purchase-order-approve` **ve** `jde_approve_purchase_order`
en la lista igual, pero al invocarlo recibe este texto explicando que no
tiene permiso. Esto es intencional (comentario en `RealmRoleGuard`: "todos
los tools entran por el mismo endpoint HTTP, así que las reglas por URL de
Spring Security no pueden distinguir 'listar' de 'aprobar'").

La pregunta que motiva este documento es distinta: que la **lista misma**
(`tools/list`) varíe según el rol — que un usuario sin el rol de aprobador ni
siquiera *vea* `jde_approve_purchase_order` como opción.

## Investigación: ¿el framework lo soporta de fábrica?

Revisado el código de Spring AI 1.1.0-M3 y del SDK Java de MCP 0.14.0
(decompilado con `javap`, no documentación de segunda mano):

1. **`@McpTool` no tiene ningún atributo de rol/visibilidad.** Los únicos
   campos son `name`, `description`, `annotations`, `generateOutputSchema`,
   `title` (verificado en `org.springaicommunity.mcp.annotation.McpTool`,
   artifact `mcp-annotations:0.5.1`). No hay un `roles = {...}` que se pueda
   simplemente agregar.

2. **Spring AI arma el servidor MCP una sola vez, al arrancar, con una lista
   fija.** `McpServerAutoConfiguration.mcpSyncServer(...)` (en
   `spring-ai-autoconfigure-mcp-server-common:1.1.0-M3`) recibe un
   `ObjectProvider<List<SyncToolSpecification>>` — se resuelve UNA vez, arma
   UN `McpSyncServer`, con la lista de tools fija desde ese momento. No hay
   ningún parámetro para pasar un predicado/filtro por request.

3. **El propio SDK (`McpAsyncServer`) guarda los tools en un
   `CopyOnWriteArrayList` con `addTool(...)`/`removeTool(...)` públicos** —
   pero es un **único bean global**, compartido por todas las sesiones/usuarios
   simultáneos. Mutar esta lista en runtime cambiaría la lista para *todo el
   mundo* conectado en ese momento, no por-caller — sirve para prender/apagar
   un tool globalmente (ej. modo mantenimiento), no para que dos usuarios
   conectados a la vez vean listas distintas.

4. **`McpServer.AsyncSpecification`/`SyncSpecification` (el builder) no
   expone ningún hook para reemplazar el handler de `tools/list`** — solo
   métodos para *registrar* tools (`tool(...)`, `tools(...)`, `toolCall(...)`),
   cada uno con un handler de **`tools/call`**, no de `tools/list`. El propio
   handler interno de `tools/list` sí recibe el `McpAsyncServerExchange` (verificado:
   `lambda$toolsListRequestHandler$16(McpAsyncServerExchange, Object)`), pero
   es privado y no hay forma de sustituirlo desde afuera.

**Conclusión concreta**: ni Spring AI ni el SDK de MCP tienen un mecanismo de
"tools/list por caller" listo para usar. Cualquier implementación real es
trabajo nuestro, encima del framework, no una property para prender.

## Dos opciones reales

### Opción A — Filtrar la respuesta de `tools/list` en un `Filter` HTTP

Igual que ya se hizo con `SlackTokenInjectionFilter` (que reescribe el
*request*), acá se necesitaría un `Filter` que reescriba la *respuesta*:

1. Detectar que el request es `tools/list` (parsear el body JSON-RPC
   entrante, mirar `"method":"tools/list"` — el mismo endpoint `/mcp` sirve
   todos los métodos).
2. Envolver la response con `ContentCachingResponseWrapper` (clase estándar
   de Spring, `org.springframework.web.util.ContentCachingResponseWrapper`)
   para poder leer/reescribir el body después de que el servidor MCP ya lo
   generó.
3. El body real tiene formato **SSE**, verificado en vivo contra el
   contenedor (no es JSON plano):
   ```
   id:<session-id>
   event:message
   data:{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"jde_approve_purchase_order",...}, ...]}}
   ```
   Hay que parsear la línea `data:`, el JSON de adentro, filtrar
   `result.tools[]` según el rol del caller (`RealmRoleGuard.hasRealmRole(...)`,
   ya disponible vía `SecurityContextHolder` en el mismo hilo del request),
   volver a serializar, y reconstruir el mismo framing SSE antes de escribir
   la respuesta real.
4. Hace falta un mapeo **tool → rol(es) requerido(s)**. Como `@McpTool` no
   tiene un atributo para esto, conviene un mapa de configuración aparte (ej.
   una property o un `Map<String, String>` en un `@Component`), no algo
   derivado automáticamente de la anotación.

**No hace falta** usar `notifications/tools/list_changed` — esa notificación
es para avisar que la lista *global* cambió (ej. se agregó un tool nuevo en
runtime) y le pide al cliente volver a pedir `tools/list`; acá cada caller ya
recibe su propia vista filtrada en cada `tools/list` que haga, sin necesidad
de avisar nada.

**Costo**: la pieza nueva y no trivial es parsear/reescribir el framing SSE
correctamente (formato ya confirmado arriba) — el resto (rol vía
`RealmRoleGuard`, patrón de `Filter`) ya existe en el proyecto.

**Beneficio real**: el usuario/LLM ni siquiera ve `jde_approve_purchase_order`
como opción si no tiene el rol — mejor UX (menos intentos fallidos, menos
confusión del LLM) y una capa extra de "no lo ofrezcas" antes de la
autorización real (que de todas formas debe seguir estando en `tools/call` —
ocultar de la lista **nunca reemplaza** el chequeo en `RealmRoleGuard`, un
cliente que ya conoce el nombre del tool podría llamarlo igual).

### Opción B — No tocar `tools/list`, solo extender el chequeo por-tool que ya existe

Mantener `tools/list` igual para todos (como hoy), y para cualquier tool
nuevo que necesite restricción por rol, replicar el patrón ya usado en
`processPurchaseOrderInternal` (chequear `roleGuard.hasRealmRole(...)` al
principio del método, devolver un texto explicativo si no corresponde).

**Costo**: cero — es el patrón que ya existe, solo se repite donde haga
falta.

**Downside**: el LLM/usuario sigue viendo el tool en la lista aunque no
pueda usarlo. Puede llevar a que el asistente lo intente, reciba el texto de
"no autorizado", y tenga que explicárselo al usuario después del hecho en
vez de no ofrecerlo directamente.

## Qué hace la industria (no hay un único consenso)

Investigado cómo resuelven esto en la práctica proveedores/plataformas que
ya publicaron su enfoque de RBAC para MCP — hay una divergencia real, no una
respuesta única:

- **Aptible** ([mcp-access-control](https://www.aptible.com/mcp-security/mcp-access-control)):
  filtran `tools/list` en una capa de **proxy/gateway** delante del server
  MCP (no adentro del server), con un modelo de "grants" `{role, server,
  tools[]}` — textual: *"enforced at the proxy layer so restricted tools are
  structurally absent from the user's tool list rather than just hidden
  client-side"*. Aun así, remarcan que el rechazo en `tools/call` en el proxy
  es *"the actual security guarantee, not filtering"* — el filtrado de la
  lista es la mejora de UX, no el control de acceso real.
- **TrueFoundry / guía práctica de RBAC para MCP**
  ([dev.to](https://dev.to/deeptishuklatfy/how-to-implement-rbac-for-mcp-tools-a-practical-guide-for-engineering-teams-fhf)):
  mismo enfoque de gateway con una "policy matrix" (rol × tool × ambiente)
  mantenida como código. Server-level access es *"necessary but not
  sufficient"* — insisten en 3 capas (server, tool, parámetro), todas
  reforzadas en el momento de la llamada, no solo al listar.
- **Google Cloud** ([control-mcp-use-iam](https://docs.cloud.google.com/mcp/control-mcp-use-iam)):
  al contrario — sus MCP servers oficiales **no filtran `tools/list` en
  absoluto**. Cita textual: *"When an MCP client calls tools/list, a list of
  all tools is returned"*, incluso con políticas IAM deny activas. El control
  de acceso es 100% infraestructura (IAM sobre el recurso de GCP que el tool
  toca), aplicado únicamente en `tools/call` — exactamente la Opción B, y es
  la elección deliberada de una plataforma grande, no una limitación.

**Ningún resultado menciona un mecanismo nativo del protocolo MCP para
esto** — confirma independientemente lo verificado decompilando el SDK:
siempre es middleware/infraestructura propia, nunca una feature del
protocolo en sí. Y las tres fuentes coinciden en algo, sin excepción: **el
chequeo en `tools/call` es obligatorio pase lo que pase con `tools/list`** —
nadie propone confiar solo en ocultar de la lista.

## Recomendación (a revisar cuando se retome esto)

No hay un "correcto" universal — es una decisión de producto, no técnica. La
industria se divide exactamente en las dos opciones de arriba, sin que una
domine:

- Si el objetivo es **UX** (que el asistente no ofrezca ni intente algo que
  el usuario no puede hacer) y hay pocos tools con esta necesidad, la Opción
  A vale la pena — es un desarrollo acotado (un `Filter` nuevo + un mapa de
  config), con precedente directo en el proyecto (`SlackTokenInjectionFilter`),
  y es el patrón que siguen Aptible/TrueFoundry.
- Si el criterio de "quién puede usar qué tool" es simple (como hoy: un solo
  rol, dos tools de purchase orders) y no es un problema real que el usuario
  vea el tool en la lista, la Opción B es más barata, ya está probada en
  producción acá mismo (es el mismo mecanismo que protege approve/reject
  hoy), y es la elección deliberada de Google Cloud para sus propios MCP
  servers — no es "la opción perezosa", es una postura válida.

**En cualquier caso**, la autorización real en `tools/call` (`RealmRoleGuard`)
tiene que seguir existiendo pase lo que pase con `tools/list` — ocultar de la
lista es una mejora de UX/superficie, no un reemplazo del control de acceso.

## Preguntas para decidir cuando se retome (no resueltas todavía)

1. ¿Cuántos tools necesitarían esta restricción hoy, y con qué roles? (define
   si conviene un mapeo de config simple o algo más elaborado)
2. ¿Vale la pena la complejidad de parsear/reescribir el framing SSE (Opción
   A), o alcanza con seguir usando el chequeo en `tools/call` (Opción B)?
3. Si se elige la Opción A: ¿el mapeo tool→rol vive en `application.properties`,
   en un `@Component` a mano, o se deriva de alguna otra fuente (ej. anotación
   custom sobre el método del tool)?
