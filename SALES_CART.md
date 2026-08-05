# Carrito de compras (Sales Cart): guía interna

Referencia técnica de cómo funciona internamente el módulo `com.atina.jdeMCPServer.cart`:
un carrito de compras en memoria que permite armar un pedido de venta multi-línea
conversacionalmente y crearlo en JDE con una única llamada BSSV
(`oracle.e1.bssv.JP420000.SalesOrderManager.processSalesOrderV5`). Complementa
`CLAUDE.md` (resumen de una línea por bullet) con el detalle de "por qué" y "cómo"
de cada pieza.

Origen: `.claude/carrito.md` (idea original del usuario) y `.claude/generaciondepedido.md`
(payload real de `processSalesOrderV5`, con request y respuesta confirmados contra el
ambiente de dev) — ambos documentos siguen siendo la fuente de verdad de las decisiones
de negocio; este archivo documenta la implementación resultante.

## Estructura de paquetes

```
cart/
├── model/     SalesCart, SalesCartLine, CartStatus, CreatedOrderRef, CartChange,
│              CartErrorCodes, SalesCartView, SalesCartLineView, y los *Result de cada tool
├── services/  SalesCartRepository (interfaz) + InMemorySalesCartRepository,
│              CartOwnerResolver, CartOwner, SalesCartService, CartOperationException,
│              CartVersionConflictException, CartValidationOutcome, SubmissionPreparation
└── tools/     JdeSalesCartTools (las 8 @McpTool)
```

`cart/` depende de `salesorder/services/JdeSalesOrderClient` (mismo cliente BSSV que usan
`jde_get_item_price`/`jde_search_items`/etc, con un método nuevo `createSalesOrder(...)`)
y de `salesorder/model/ToolStatus` (reusado tal cual, sin duplicar el enum).

## 1. Modelo de dominio: records inmutables + mutación vía `with*`

`SalesCart` es un `record` inmutable (sin Lombok, sin setters — mismo criterio que el
resto del proyecto). Cada mutación de negocio es un método `with*` que devuelve una
instancia **nueva**, nunca modifica la existente:

```java
public record SalesCart(
        String cartId, String sessionId, String ownerId, String tenantId,
        Integer customerId, String customerName, Integer shipToId,
        String businessUnit, String company, String orderType, String currencyCode,
        List<SalesCartLine> lines, CartStatus status, long version,
        Instant createdAt, Instant updatedAt, Instant expiresAt, CreatedOrderRef createdOrder
)
```

Métodos `with*` disponibles: `withLineAdded`, `withLineUpdated`, `withLineRemoved`,
`withLinesReplaced` (usado por validate), `withStatus`, `withVersionIncrementedAndTouched`
(incrementa `version` y, si el carrito estaba `READY_FOR_CONFIRMATION`, lo vuelve a
`OPEN`), `withCreatedOrder`, `withSessionId` (re-homing, ver sección 3), `withExpiresAt`
(TTL, ver sección 4). Ninguno muta `this` — todos devuelven un `SalesCart` nuevo que el
repositorio es quien efectivamente persiste.

**Campos con supuestos/simplificaciones deliberadas** (documentados en el javadoc del
record, repetidos acá para que no se pierdan):
- `tenantId`: no hay ninguna fuente real de multi-tenant en el proyecto hoy — queda `""`.
- `shipToId`: se resuelve siempre igual a `customerId`. JDE distingue conceptualmente
  `invoicedTo`/`deliverTo`/`shipTo` (ver `.claude/generaciondepedido.md`), pero el carrito
  no tiene hoy una forma de que el usuario informe una dirección de envío distinta.
- `cartId`: **no** es un UUID completo (36 caracteres) — son 12 hex sin guiones
  (`SalesCart.newCartId()`, 48 bits de un `UUID.randomUUID()`, colisión despreciable para
  el volumen de carritos en memoria de esta etapa). Motivo: la referencia externa que se
  arma como `"MCP-{cartId}-{version}"` (`SalesCartService.buildExternalReference`) se
  envía a JDE como `attachmentText`/`reference` en `processSalesOrderV5`, y ese campo
  **no puede superar 30 caracteres** (límite real del backend, confirmado en producción —
  mismo límite que ya truncaba el `remark` de aprobar/rechazar purchase orders en
  `JdePurchaseApprovalTool`). Un UUID completo ya solo excede ese límite; con el cartId
  corto, `buildExternalReference` además trunca defensivamente a 30 por las dudas.

`SalesCartLine` es el mismo patrón, con un método `withRecalculatedPrice(...)` (usado por
update/validate) y `withQuantity(...)` (usado por update).

## 2. `SalesCartRepository` / `InMemorySalesCartRepository`: repositorio en memoria

Calcado de `purchase/services/PendingPurchaseOrderStore` (mismo criterio en todo el
proyecto: `ConcurrentHashMap` keyed por un id de negocio, TTL configurable, evicción
perezosa + barrido `@Scheduled`):

```java
public interface SalesCartRepository {
    Optional<SalesCart> findBySessionId(String sessionId);
    Optional<SalesCart> findByOwnerId(String ownerId);        // ver sección 3
    SalesCart save(SalesCart cart);
    SalesCart rehome(SalesCart cart, String newSessionId);    // ver sección 3
    SalesCart update(String sessionId, UnaryOperator<SalesCart> mutator);
    void deleteBySessionId(String sessionId);
    void removeExpired();
    int activeCount();
}
```

**Por qué `ConcurrentHashMap.compute()` y no `synchronized(cart)`**: `SalesCart` es
inmutable, así que sincronizar sobre una instancia no protege nada — en cuanto otro hilo
reemplaza la entrada del mapa, esa instancia queda obsoleta. `update(sessionId, mutator)`
hace `carts.compute(sessionId, (k, current) -> mutator.apply(current))`: `compute()`
corre el mutator de forma atómica respecto de otras operaciones sobre la **misma key**
(lock interno del propio `ConcurrentHashMap` por bucket) — evita perder un incremento de
`version` entre dos llamadas casi simultáneas de la misma sesión, sin locks explícitos.
Si el mutator lanza una excepción de negocio, la entrada existente queda intacta (el
mapping function no completó normalmente). Todos los métodos de `SalesCartService` que
mutan el carrito pasan un mutator a `update(...)`; ninguno hace `find` + modificar +
`save` por separado, para no perder esa atomicidad.

**TTL deslizante**: `save()` y `update()` recalculan `expiresAt = Instant.now() + ttl`
cada vez (`jde.cart.ttl-minutes`, default 120) — no es un TTL fijo desde la creación, se
renueva con cada operación sobre el carrito. `findBySessionId`/`findByOwnerId` evictan
perezosamente si `cart.isExpired()`; además hay un barrido `@Scheduled(fixedRateString =
"${jde.cart.cleanup-interval-minutes:15}", ...)` que purga carritos vencidos aunque nadie
los vuelva a pedir.

**Límite de carritos activos**: `save()` rechaza con `CART_LIMIT_EXCEEDED` si se supera
`jde.cart.max-active-carts` (default 500) — chequeo simple (`containsKey` + `size()`, no
atómico end-to-end), aceptable como protección básica de memoria en esta etapa sin Redis.

## 3. Identidad del carrito: `sessionId`, `ownerId`, y el fallback con re-homing

Ninguna tool recibe `sessionId`/`userId`/`tenantId` como parámetro — se resuelven
server-side. `CartOwnerResolver.resolveCurrent()` arma un `CartOwner(sessionId, ownerId)`:

- `sessionId` = `JdeAuthService.resolveSessionId()` (ya existente, reusado tal cual): lee
  el header `Mcp-Session-Id`; si no viene, cae a `request.getRemoteAddr()`.
- `ownerId` = `AuthenticatedJdeIdentity.currentSubject()` (el `sub` del JWT de Keycloak).
  Si el caller es el microservicio Atina directo (sin JWT Keycloak real, `sub` no
  disponible), degrada a usar el propio `sessionId` como `ownerId` — mismo nivel de
  aislamiento que ya usa `JdeTokenStore` en ese escenario.

### El bug real que motivó el fallback por `ownerId`

Confirmado contra logs de producción: el conector remoto de Claude.ai puede reinicializar
la sesión MCP (`Client initialize request`) entre dos tool calls de la misma conversación,
emitiendo un `Mcp-Session-Id` **distinto** para la siguiente llamada — sin que el usuario
haya hecho nada visible. Como el carrito estaba indexado solo por `sessionId`, esto hacía
que `jde_validate_current_sales_cart` devolviera `CART_NOT_FOUND` inmediatamente después
de un `jde_add_item_to_current_sales_cart` exitoso. Hipótesis del disparador: una llamada
lenta (se observó un caso de ~53s en `jde_add_item_to_current_sales_cart`, resolviendo
precio contra JDE) puede hacer que el cliente remoto dé la conexión por muerta y reconecte.

`SalesCartService.findActiveCart(owner)` (usado por las 7 operaciones que necesitan un
carrito existente) resuelve esto:

```java
private Optional<SalesCart> findActiveCartOptional(CartOwner owner) {
    Optional<SalesCart> bySession = repository.findBySessionId(owner.sessionId());
    if (bySession.isPresent()) {
        return bySession;                                    // camino rápido
    }
    return repository.findByOwnerId(owner.ownerId())
            .map(cart -> repository.rehome(cart, owner.sessionId()));  // fallback + re-home
}
```

Si el camino rápido (`findBySessionId`) falla, busca por `ownerId` (scan lineal sobre
`carts.values()` — barato, solo corre en un miss, acotado por `max-active-carts`). Si lo
encuentra, `rehome(cart, newSessionId)` quita la entrada vieja del mapa y guarda el
carrito con `withSessionId(newSessionId)` bajo la key nueva — las llamadas siguientes de
esa misma sesión ya lo encuentran por el camino rápido.

**Límite conocido**: si `ownerId` degrada a `sessionId` (caller Atina sin Keycloak real),
el fallback no ayuda — en ese modo, `ownerId` cambia junto con `sessionId`, así que no hay
ninguna señal de identidad estable que sobreviva a la reconexión.

## 4. Máquina de estados (`CartStatus`)

```
OPEN ⇄ READY_FOR_CONFIRMATION → SUBMITTING → ORDER_CREATED
  ↑___________________________________|          (terminal, nunca se reenvía)
CANCELLED / EXPIRED (terminales, no bloquean crear uno nuevo)
VALIDATING — declarado en el enum, sin uso real hoy (ver "fuera de alcance")
```

- **`isEditable()`** = `OPEN` o `READY_FOR_CONFIRMATION`. Add/update/remove/validate
  exigen esto (`SalesCartService.checkEditable`); si no, `CART_NOT_EDITABLE`.
- **Add/update/remove** → `withVersionIncrementedAndTouched()`: incrementa `version` y,
  si el carrito estaba `READY_FOR_CONFIRMATION`, lo vuelve a `OPEN` — cualquier cambio de
  línea después de haber validado invalida esa validación.
- **Validate** (`SalesCartService.validateCart`) → si no hay cambios, pasa a
  `READY_FOR_CONFIRMATION`; si los hay, vuelve/permanece `OPEN`. **No** incrementa
  `version` (ver sección 5) — es un resync contra JDE, no una decisión nueva del usuario.
- **Submit** (`prepareSubmission`) → marca `SUBMITTING` atómicamente antes de llamar al
  Gateway (bloquea un segundo submit concurrente: una llamada que llega mientras otra ya
  está `SUBMITTING` es rechazada con `ORDER_SUBMISSION_FAILED`, salvo que sea exactamente
  la misma versión — ver sección 7). Éxito → `ORDER_CREATED` (terminal, `jde_submit_...`
  nunca reenvía un carrito en ese estado, devuelve el pedido ya creado). Error → vuelve a
  `OPEN` (`failSubmission`) — no hay ninguna transacción JDE que revertir, solo se deja el
  carrito consistente para reintentar.
- **Clear** (`jde_clear_current_sales_cart`) → borra la entrada del mapa, **salvo** si
  `status == ORDER_CREATED`, en cuyo caso se preserva (no se pierde la trazabilidad de un
  pedido ya creado) hasta que expire por TTL o se cree un carrito nuevo.

## 5. El flujo completo, tool por tool

Las 8 tools viven en `cart/tools/JdeSalesCartTools.java` (un único `@Component`). Ninguna
contiene lógica de negocio directamente — validan la forma de los parámetros (nulls,
cantidades ≤ 0) y traducen `CartOperationException`/`CartVersionConflictException` a los
`*Result` estructurados vía el helper `statusFor(errorCode)` (mapea a `INVALID_REQUEST`
si el código está en la lista de "errores de uso/estado", a `FAILED` si no).

| Tool | Método de servicio | Qué hace | Toca JDE |
|---|---|---|---|
| `jde_create_current_sales_cart` | `createCart` | Crea el carrito, fija cliente/moneda (resuelve moneda vía `getCustomerDetail` si no se informa). Rechaza con `CART_ALREADY_EXISTS` si ya hay uno activo (por sessionId o por ownerId). | Sí (getCustomerDetail) |
| `jde_add_item_to_current_sales_cart` | `addLine` | Auto-crea el carrito si no existe y viene `entityId`. Resuelve precio específico del cliente (`getCustomerItemPrice`/`getItemPriceAndAvailability` según `jde.cart.check-availability-on-add`), agrega la línea, incrementa versión. | Sí |
| `jde_update_current_sales_cart_item` | `updateLine` | Recalcula precio con la nueva cantidad/UM, incrementa versión. | Sí |
| `jde_remove_current_sales_cart_item` | `removeLine` | Quita la línea, incrementa versión. `CART_LINE_NOT_FOUND` si ya no existe (no es un no-op silencioso). | No |
| `jde_get_current_sales_cart` | `getCart` | Solo lectura, no revalida contra JDE. Sin carrito → `status=OK` + `errorCode=CART_NOT_FOUND` (estado normal, no una falla). | No |
| `jde_validate_current_sales_cart` | `validateCart` | Ver sección 6. | Sí |
| `jde_clear_current_sales_cart` | `clearCart` | Requiere `confirm=true`. Preserva el carrito si `ORDER_CREATED`. | No |
| `jde_submit_current_sales_cart` | `prepareSubmission` + `soClient.createSalesOrder(...)` + `finalizeSubmission`/`failSubmission` | Ver sección 7. | Sí (la única escritura) |

Todas las tools que devuelven un carrito lo hacen como `SalesCartView` (`cart/model/`,
proyección serializable de `SalesCart` con todos los campos garantizados no-`null` —
`SalesCartView.empty()` para los casos de error, mismo criterio "nunca null" que el resto
del proyecto porque el SDK MCP valida `structuredContent` contra el `outputSchema`).

## 6. `jde_validate_current_sales_cart` en detalle

Re-consulta JDE por cada línea (precio siempre; disponibilidad si
`jde.cart.check-availability-on-validate=true`, default `true`) usando los mismos
identificadores ya guardados en la línea (`itemId`/`itemCatalog`, `businessUnit`,
`currencyCode`) y la cantidad **actual**. Compara contra lo guardado:

- Precio distinto → `CartChange(lineId, "unitPrice", anterior, actual)`.
- Disponibilidad insuficiente para la cantidad pedida (solo si el check está activo) →
  `CartChange(lineId, "availableQuantity", anterior, actual)`.

Actualiza **todas** las líneas con los valores recalculados (haya cambios o no).
Resultado:
- **Sin cambios** → `status=OK`, `requiresReconfirmation=false`, carrito pasa a
  `READY_FOR_CONFIRMATION`.
- **Con cambios** → `status=OK`, `requiresReconfirmation=true`, `changes[]` poblado,
  carrito vuelve/permanece `OPEN` — la tool le exige al modelo mostrar los cambios y
  pedir confirmación de nuevo antes de `jde_submit_current_sales_cart`.

**No incrementa `version`**: a diferencia de add/update/remove, no representa una nueva
decisión del usuario, solo resincroniza contra JDE — así el `expectedCartVersion` que el
usuario vio en la última consulta sigue siendo válido para el submit posterior.

## 7. `jde_submit_current_sales_cart`: confirmación, versión, y el único write

La única tool que efectivamente crea algo en JDE. Dos fases separadas a propósito:

### Fase 1 — `SalesCartService.prepareSubmission(expectedCartVersion)` (siempre síncrona)

Corre en el hilo del request (necesita `CartOwnerResolver`, que depende de
`RequestContextHolder`/`SecurityContextHolder` — no disponible en un hilo de background,
ver sección 8). Validaciones en orden, cada una con su `errorCode` si falla, sin llamar
al Gateway:

1. `status == ORDER_CREATED` → devuelve el pedido ya creado
   (`recoveredFromExistingOrder=true`), no llama a JDE de nuevo.
2. `status == SUBMITTING` con la **misma** `expectedCartVersion` → no es un error, es un
   poll de una tarea async en curso (o un reintento del cliente MCP): reconstruye el mismo
   `request`/`externalReference` de forma determinística sin volver a mutar el estado.
   Versión distinta → `CART_VERSION_CONFLICT`.
3. No editable / vacío / versión no coincide / moneda no resuelta → error específico.
4. Chequeo de crédito opcional (`jde.cart.check-credit-on-submit`): si excede el límite,
   bloquea con `CREDIT_LIMIT_EXCEEDED` (si `jde.cart.block-on-credit-exceeded=true`) o
   solo agrega una advertencia al mensaje final.
5. Marca `SUBMITTING` **atómicamente** vía `repository.update(...)` — esto es lo que
   bloquea un segundo submit concurrente exitoso (una llamada que llega mientras esta
   sigue en curso ve `SUBMITTING` con otra versión, o la misma si es el propio poll).
6. Arma la referencia externa `"MCP-{cartId}-{version}"` (sección 1) y el
   `CreateSalesOrderRequest` completo (sección 9).

Devuelve un `SubmissionPreparation` (record interno: `cart`, `recovered`,
`recoveredOrder`, `request`, `externalReference`, `warning`) — nunca llama al Gateway.

### Fase 2 — la tool llama al Gateway (síncrona o vía `LongRunningTaskRegistry`)

`jde.sales-order.submit.async.enabled` (default `true`, mismo kill-switch que las demás
tools async del proyecto) decide:

- **`false`**: `soClient.createSalesOrder(prep.request())` directo, bloqueante.
- **`true`**: resuelve el token JDE en el hilo del request (`soClient.resolveSessionToken()`
  — regla de oro del patrón async, ver `LONG_RUNNING_TOOLS.md`), arma una key
  `"submit-cart|" + sessionId + "|" + externalReference"`, y llama
  `taskRegistry.getOrStart(key, ..., () -> soClient.createSalesOrderWithToken(prep.request(), token))`.
  Si no termina dentro de `jde.sales-order.submit.async.initial-wait-seconds`, devuelve
  `IN_PROGRESS` con `pollAfterSeconds` — el modelo debe reintentar con el **mismo**
  `expectedCartVersion`/`confirm=true`, lo que en la Fase 1 cae en el caso 2 de arriba
  (mismo `externalReference` reconstruido → misma `key` → reconecta con la tarea existente
  en vez de arrancar una nueva).

Al terminar (síncrono o `COMPLETED` del registry): `SalesCartService.finalizeSubmission`
guarda el `CreatedOrderRef` y marca `ORDER_CREATED`. Si falla (excepción, `FAILED` o
`CANCELLED` del registry): `SalesCartService.failSubmission` revierte a `OPEN`.

**Confirmación explícita**: `confirm` debe ser `true` — se valida como parámetro plano
en la tool (no vía `CartOperationException`), con la descripción del `@McpTool` insistiendo
en que "agregar/consultar/validar NO cuenta como confirmación" y dando ejemplos de
confirmaciones válidas/inválidas, mismo criterio que el resto del proyecto usa para
decisiones irreversibles.

**Control de versión optimista**: `expectedCartVersion` (obligatorio) se compara contra
`cart.version()` en la Fase 1; si no coincide, `CartVersionConflictException` (subclase de
`CartOperationException` que además carga `currentVersion` — el único error que necesita
un dato estructurado extra más allá de `errorCode`/`message`).

## 8. Por qué `prepareSubmission` nunca llama al Gateway directamente

`LongRunningTaskRegistry` corre el `Supplier` en un `ExecutorService` propio, **sin**
`RequestContextHolder` — si `prepareSubmission` (que necesita `CartOwnerResolver`, que
necesita `RequestContextHolder`) corriera dentro del `Supplier`, fallaría en el camino
async. Por eso el diseño separa: todo lo que necesita el contexto del request (resolver
owner, leer/mutar el repositorio, armar el payload) corre siempre en el hilo del request
(Fase 1); solo la llamada HTTP en sí al Gateway (que no depende de `RequestContextHolder`,
usa `createSalesOrderWithToken` con el token ya resuelto como dato plano) puede diferirse
al hilo de background (Fase 2). Mismo patrón exacto que usan `jde_search_items`/
`jde_get_item_price` — ver `LONG_RUNNING_TOOLS.md` para el detalle general del motor.

## 9. Construcción del payload `processSalesOrderV5`

`SalesCartService.buildRequest(cart, externalReference)` arma un
`CreateSalesOrderRequest` (`salesorder/model/`) que `JdeSalesOrderClient.createSalesOrder`
convierte al JSON real (ver ese archivo y `.claude/generaciondepedido.md` para el payload
completo). Puntos no obvios:

- **`invoicedTo`/`deliverTo`/`shipTo.customer`**: los tres usan `cart.customerId()` (ver
  limitación de `shipToId` en la sección 1).
- **`company` vs `documentCompany`**: campos **distintos** con distinto padding
  (`jde.sales-order.company` = `"0001"`, `jde.sales-order.document-company` = `"00001"`)
  — no normalizar como si fueran el mismo campo, confirmado en `generaciondepedido.md`.
- **`businessUnit`** (header y cada línea): paddeado a 12 caracteres
  (`JdeSalesOrderClient.padBusinessUnit`, mismo helper que usan las operaciones de
  precio). El de header sale de `cart.businessUnit()`, o si está vacío, del `businessUnit`
  de la primera línea.
- **`itemProduct`** por línea (`resolveItemProduct`): `itemCatalog` (recortado) si está
  informado, si no `String.valueOf(itemId)`. Confirmado empíricamente contra una respuesta
  real que `itemProduct` coincide con `itemCatalog` sin padding.
- **`dateRequested` vs `dateOrdered`**: `dateOrdered` = hoy. `dateRequested` = hoy +
  `jde.sales-order.default-requested-date-lead-days` (default 30) — **nunca igual a
  `dateOrdered`**. Confirmado contra un envío real: si ambas fechas coinciden, JDE no
  tiene margen para calcular la fecha de pick/promised-ship contra el lead time del
  business unit/artículo y devuelve un warning. Ver `.claude/generaciondepedido.md`,
  sección "Warning de fecha de pick".
- **`reference`/`attachmentText`**: la misma `externalReference` (`"MCP-{cartId}-{version}"`)
  en las dos, límite de 30 caracteres (sección 1).
- **`documentTypeCode`/`lineTypeCode`/`actionType`/`processingVersion`**: todos vienen de
  `jde.sales-order.*` — nunca decididos por el modelo LLM (mismo principio que
  `jde.purchase.p43081-version` para purchase orders).

`JdeSalesOrderClient.parseCreateSalesOrderResponse` parsea la respuesta según una
estructura **confirmada contra un envío real** (no un supuesto): `listaDeValores` es un
objeto directo (no array) con `header` adentro; el número de pedido real es
`header.salesOrderKey.documentNumber` (no `documentOrderInvoiceNumber`, que es un campo
de purchase orders); la moneda de respuesta vive en `header.financial.currencyCode`. Ver
`.claude/generaciondepedido.md` para el JSON completo de esa respuesta.

## 10. Idempotencia: qué protege y qué no (límite real, no resuelto)

Guardar `createdOrder` en el `SalesCart` protege reintentos **dentro de la misma sesión
MCP activa, sin reinicio del servidor, dentro del TTL del carrito** (sección 7, caso 1 de
`prepareSubmission`). Lo que **no** protege, sin resolver hoy:

1. **Reinicio del servidor entre la llamada BSSV real y la confirmación**: el
   `ConcurrentHashMap` es 100% en memoria — un reinicio justo después de que JDE creó el
   pedido pero antes de que se marque `ORDER_CREATED` (o antes de que la respuesta llegue
   al cliente) borra todo rastro, y un reintento puede duplicar el pedido en JDE.
2. **Dos sesiones MCP distintas** para "el mismo pedido" no comparten carrito.
3. **Reintento después de que el carrito expiró por TTL**.
4. La referencia externa se guarda en `attachmentText` (confirmado que JDE la persiste y
   la devuelve en la misma respuesta de creación — sección 9), pero **no existe hoy
   ninguna operación en `JdeSalesOrderClient` para buscar un pedido por esa referencia**.
   Sin eso, el flujo "buscar antes de crear" que idealmente daría idempotencia real
   cross-reinicio no se puede implementar del lado servidor.

Por eso `jde_submit_current_sales_cart` es `destructiveHint`-equivalente en su
descripción (aunque el proyecto no usa formalmente los hints MCP, ver sección 12) y trata
cada llamada con `confirm=true` como una escritura real e irreversible, no como una
operación segura de reintentar a ciegas.

## 11. Configuración

Todas comunes (`application.properties`, no hay overrides por perfil):

| Property | Default | Qué controla |
|---|---|---|
| `jde.cart.ttl-minutes` | 120 | TTL deslizante del carrito (sección 2) |
| `jde.cart.cleanup-interval-minutes` | 15 | Frecuencia del barrido `@Scheduled` |
| `jde.cart.max-active-carts` | 500 | Límite de carritos simultáneos en memoria |
| `jde.cart.check-availability-on-add` | `false` | `getItemPriceAndAvailability` vs `getCustomerItemPrice` al agregar/actualizar una línea |
| `jde.cart.check-availability-on-validate` | `true` | Idem, en `jde_validate_current_sales_cart` |
| `jde.cart.check-credit-on-submit` | `false` | Consulta `getCustomerCreditInfo` antes de crear el pedido |
| `jde.cart.block-on-credit-exceeded` | `false` | Si excede: bloquear (`CREDIT_LIMIT_EXCEEDED`) o solo advertir |
| `jde.sales-order.document-type-code` | `SO` | `salesOrderKey.documentTypeCode` |
| `jde.sales-order.company` | `0001` | `header.company` |
| `jde.sales-order.document-company` | `00001` | `salesOrderKey.documentCompany` |
| `jde.sales-order.line-type-code` | `S` | `detail[].lineTypeCode` |
| `jde.sales-order.action-type` | `A` | `processing.actionType` (header y línea) |
| `jde.sales-order.processing-version` | `ZJDE0001` | `header.processing.processingVersion` |
| `jde.sales-order.default-requested-date-lead-days` | 30 | Días entre `dateOrdered` y `dateRequested` (sección 9) |
| `jde.sales-order.submit.async.enabled` | `true` | Kill-switch del submit vía `LongRunningTaskRegistry` |
| `jde.sales-order.submit.async.initial-wait-seconds` | 8 | Espera antes de devolver `IN_PROGRESS` |

## 12. Errores: `CartErrorCodes` + `ToolStatus`

`salesorder.model.ToolStatus` (`OK`/`INVALID_REQUEST`/`IN_PROGRESS`/`FAILED`/`CANCELLED`)
se reusa sin cambios — **no tiene** `UNAUTHORIZED` (a diferencia del `ToolStatus` de
`purchase`, un enum distinto), porque el carrito no tiene control de rol Keycloak; la
propiedad se resuelve con `CART_ACCESS_DENIED`, un concepto distinto (dueño vs. rol).

Los `*Result` del carrito agregan un campo `String errorCode` (además de
`status`/`message`) con los códigos de negocio específicos
(`cart/model/CartErrorCodes`): `CART_NOT_FOUND`, `CART_ACCESS_DENIED`, `CART_EXPIRED`,
`CART_NOT_EDITABLE`, `CART_EMPTY`, `CART_ALREADY_EXISTS`, `CART_LIMIT_EXCEEDED`,
`CART_LINE_NOT_FOUND`, `CART_VERSION_CONFLICT`, `CUSTOMER_MISMATCH`, `ITEM_NOT_FOUND`,
`PRICE_NOT_FOUND`, `PRICE_CHANGED`, `INSUFFICIENT_AVAILABILITY`, `CURRENCY_NOT_RESOLVED`,
`CREDIT_LIMIT_EXCEEDED`, `ORDER_ALREADY_CREATED`, `ORDER_SUBMISSION_FAILED` (más `NONE`
= `""` cuando no aplica). Internamente, `SalesCartService` lanza `CartOperationException`
(con `errorCode`) para cada uno de estos casos; la tool los atrapa y usa
`JdeSalesCartTools.statusFor(errorCode)` para decidir `INVALID_REQUEST` (errores de
uso/estado del carrito) vs `FAILED` (fallas técnicas/de backend).

## 13. Fuera de alcance / limitaciones conocidas (a propósito)

- **`CartStatus.VALIDATING`**: declarado en el enum, sin ninguna transición real hacia/desde
  él hoy — `validateCart()` corre y resuelve directo a `OPEN`/`READY_FOR_CONFIRMATION`
  de forma síncrona, nunca deja el carrito en un estado intermedio "validando".
- **Hints MCP** (`readOnlyHint`/`destructiveHint`/`idempotentHint`/`openWorldHint`): la
  idea original (`.claude/carrito.md`) proponía anotarlos por tool, pero **no están
  implementados** — ningún tool de todo el proyecto (ni carrito, ni purchase, ni
  salesorder) los usa hoy; el comportamiento equivalente vive solo en el texto de la
  descripción de cada `@McpTool`.
- **Sin Redis/Postgres**: decisión explícita de la primera etapa (`.claude/carrito.md`).
  `SalesCartRepository` es una interfaz precisamente para poder reemplazar
  `InMemorySalesCartRepository` por una implementación con Redis (optimistic locking
  `WATCH`/`MULTI`/`EXEC`, o `RLock` distribuido) sin tocar `SalesCartService` ni las
  tools el día que haga falta escalar a más de una instancia del MCP Server.
- **Sin búsqueda de pedido por referencia externa en JDE**: bloquea la idempotencia real
  cross-reinicio (sección 10) — requiere una operación BSSV nueva del lado de JDE/Atina,
  fuera del control de este proyecto.
