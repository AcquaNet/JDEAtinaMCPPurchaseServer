Te dejo un prompt listo para copiar en Claude, orientado a que analice tu MCP actual y proponga una implementación concreta, incremental y compatible con las tools existentes.

Actúa como arquitecto de software senior especializado en Java, Spring Boot, Spring AI, Model Context Protocol (MCP) y Oracle JD Edwards EnterpriseOne.

Tengo un MCP Server de JDE ya implementado. El servidor expone tools relacionadas con ventas, clientes, productos, precios, disponibilidad y aprobación de órdenes de compra.

Entre las tools existentes se encuentran:

* jde_search_items
* jde_get_item_list_price
* jde_get_item_price
* jde_lookup_customer_by_name
* jde_get_customer_detail
* jde_get_customer_credit_info
* jde_list_pending_purchase_orders
* jde_get_purchase_order_detail
* jde_approve_purchase_order
* jde_reject_purchase_order

Las tools usan JSON Schema para sus entradas y salidas, estados estructurados como:

* OK
* INVALID_REQUEST
* UNAUTHORIZED
* IN_PROGRESS
* FAILED
* CANCELLED

Algunas operaciones contra JDE son asíncronas y pueden devolver IN_PROGRESS junto con pollAfterSeconds.

Objetivo

Quiero incorporar al MCP Server la posibilidad de armar un carrito de compras y, finalmente, generar un pedido de venta en JDE.

El flujo conversacional esperado es similar a este:

1. El usuario identifica un cliente.
2. El usuario consulta uno o más productos.
3. El MCP resuelve los productos mediante jde_search_items.
4. El MCP consulta el precio específico para el cliente mediante jde_get_item_price.
5. Opcionalmente consulta disponibilidad.
6. El usuario decide agregar el producto al carrito.
7. El usuario puede consultar, modificar o vaciar el carrito.
8. Antes de generar el pedido, se deben volver a validar precios, cantidades, cliente, moneda y disponibilidad.
9. El MCP muestra un resumen final.
10. El usuario confirma explícitamente.
11. El MCP genera el pedido de venta en JDE.
12. Debe evitarse la generación duplicada del pedido ante reintentos.

Alcance de la primera etapa

Quiero implementar una primera versión simple, sin agregar infraestructura nueva.

Las decisiones iniciales son:

* Un solo carrito activo por sesión MCP.
* Almacenamiento en memoria.
* Sin Redis.
* Sin PostgreSQL.
* Identificación principal mediante sessionId.
* Validación adicional mediante el usuario autenticado.
* cartId interno para trazabilidad.
* Una referencia externa única para evitar pedidos duplicados en JDE.
* Una sola instancia activa del MCP Server.
* El carrito puede perderse si el servidor se reinicia.
* Los pedidos ya creados deben permanecer en JDE.
* El carrito debe expirar luego de un tiempo configurable.
* La implementación debe quedar preparada para reemplazar posteriormente el repositorio en memoria por Redis sin modificar las tools ni la lógica de negocio.

Responsabilidades

Quiero que el MCP funcione como adaptador para IA y que la lógica del carrito quede en servicios internos.

La separación esperada es:

MCP Tools
↓
SalesCartService
↓
SalesCartRepository
↓
InMemorySalesCartRepository

Y para la integración:

SalesCartService
├── servicio de productos
├── servicio de precios
├── servicio de disponibilidad
└── servicio de creación de pedidos JDE

Las tools MCP no deberían contener directamente toda la lógica del carrito.

Identificación del carrito

El carrito debe utilizar:

* sessionId como clave para localizar el carrito activo.
* cartId como identificador interno de negocio y trazabilidad.
* userId o identificador del usuario autenticado para verificar propiedad.
* tenantId, entorno JDE u otros datos de seguridad cuando estén disponibles.

No quiero que el modelo envíe libremente el sessionId, el userId o el tenantId como argumentos de las tools.

Estos valores deben obtenerse desde el contexto MCP y desde el contexto de autenticación.

Conceptualmente:

String sessionId = mcpSessionContext.getSessionId();
AuthenticatedUser user = userContextService.getCurrentUser();

Modelo inicial del carrito

Propón un modelo similar a:

SalesCart
- cartId
- sessionId
- userId
- tenantId
- customerId
- customerName
- shipToId
- branchPlant
- company
- orderType
- currencyCode
- lines
- status
- version
- createdAt
- updatedAt
- expiresAt
- createdOrder

Cada línea debería contener al menos:

SalesCartLine
- lineId
- itemId
- itemCatalog
- description
- quantity
- unitOfMeasure
- businessUnit
- unitPrice
- extendedPrice
- currencyCode
- availableQuantity
- priceCalculatedAt

Estados del carrito

Propón una máquina de estados sencilla:

OPEN
VALIDATING
READY_FOR_CONFIRMATION
SUBMITTING
ORDER_CREATED
CANCELLED
EXPIRED

Reglas esperadas:

* Solo se puede modificar un carrito en estado OPEN.
* Antes de enviar el pedido debe pasar por VALIDATING.
* Si la validación termina correctamente pasa a READY_FOR_CONFIRMATION.
* Al comenzar el envío pasa a SUBMITTING.
* Al finalizar correctamente pasa a ORDER_CREATED.
* Si cambia una línea luego de validar, debe volver a OPEN.
* Un carrito ORDER_CREATED no debe volver a enviarse.

Repositorio en memoria

Quiero utilizar inicialmente algo equivalente a:

ConcurrentMap<String, SalesCart> cartsBySessionId;

La clave será el sessionId.

Debe existir una interfaz:

public interface SalesCartRepository {
SalesCart save(SalesCart cart);
Optional<SalesCart> findBySessionId(String sessionId);
void deleteBySessionId(String sessionId);
void removeExpired();
}

Y una implementación:

InMemorySalesCartRepository

La solución debe contemplar:

* ConcurrentHashMap.
* Expiración configurable.
* Limpieza periódica con @Scheduled.
* Límite máximo de carritos activos.
* Validación de pertenencia por usuario autenticado.
* Protección básica de concurrencia sobre un mismo carrito.
* Una sola instancia del MCP en esta primera fase.

Tools nuevas

Evalúa y diseña las siguientes tools:

jde_create_current_sales_cart
jde_add_item_to_current_sales_cart
jde_update_current_sales_cart_item
jde_remove_current_sales_cart_item
jde_get_current_sales_cart
jde_validate_current_sales_cart
jde_submit_current_sales_cart
jde_clear_current_sales_cart

Puedes ajustar los nombres si propones una nomenclatura más coherente con las tools actuales, pero mantén el prefijo jde_.

Para cada tool necesito:

1. Nombre.
2. Propósito.
3. Cuándo usarla.
4. Cuándo no usarla.
5. Secuencia esperada con otras tools.
6. Input Schema.
7. Output Schema.
8. Estados posibles.
9. Annotations MCP:
    * readOnlyHint
    * destructiveHint
    * idempotentHint
    * openWorldHint
10. Descripción completa optimizada para que el modelo use correctamente la tool.
11. Reglas explícitas para impedir que el modelo invente identificadores.
12. Manejo de IN_PROGRESS cuando dependa de operaciones JDE asíncronas.

Integración con las tools existentes

La propuesta debe reutilizar las tools y servicios existentes.

Cliente

Cuando el usuario proporciona solo el nombre:

jde_lookup_customer_by_name
↓
entityId

Si devuelve exactamente un cliente, continuar automáticamente.

Si devuelve varios, pedir al usuario que elija.

Producto

Cuando el usuario proporciona una descripción:

jde_search_items
↓
itemCatalog + itemId

Si devuelve exactamente un producto, continuar automáticamente.

Si devuelve varios, mostrar opciones y pedir al usuario que elija.

Precio

Para agregar una línea al carrito debe utilizarse el precio específico del cliente:

jde_get_item_price

No debe utilizarse jde_get_item_list_price como precio definitivo de una línea de pedido cuando existe un cliente asociado.

Moneda

Si el usuario no proporcionó moneda, debe resolverse mediante:

jde_get_customer_detail

usando la moneda de transacción del cliente disponible en la respuesta actual.

Crédito

Antes del envío final, analiza si conviene integrar:

jde_get_customer_credit_info

No quiero necesariamente bloquear el pedido por crédito en esta primera etapa, pero sí quiero que expliques cómo se podría:

* mostrar una advertencia;
* bloquear el checkout;
* dejarlo configurable.

Comportamiento de jde_add_item_to_current_sales_cart

La tool debería recibir únicamente información de negocio, por ejemplo:

{
"entityId": 4242,
"itemCatalog": "210",
"itemId": 60011,
"quantity": 2,
"unitOfMeasure": "EA",
"businessUnit": "10",
"currencyCode": "USD"
}

No debe recibir:

* sessionId
* userId
* tenantId

La implementación debe:

1. Obtener el sessionId desde el contexto MCP.
2. Obtener el usuario autenticado.
3. Buscar el carrito activo de la sesión.
4. Crear uno cuando corresponda o devolver un error claro si prefieres creación explícita.
5. Verificar que el carrito pertenezca al usuario autenticado.
6. Verificar que el cliente coincida con el cliente del carrito.
7. Consultar el producto.
8. Consultar precio específico del cliente.
9. Consultar disponibilidad si fue solicitada o si la política del carrito lo exige.
10. Agregar la línea.
11. Incrementar la versión.
12. Devolver el carrito actualizado.

Analiza si conviene:

* exigir primero jde_create_current_sales_cart; o
* crear el carrito automáticamente al agregar el primer producto.

Recomienda una opción y justifícala según simplicidad y confiabilidad del agente.

Validación previa al checkout

jde_validate_current_sales_cart debe volver a comprobar:

* que el carrito tenga líneas;
* que el cliente siga siendo válido;
* moneda;
* producto;
* cantidad;
* unidad de medida;
* almacén o business unit;
* precio actual;
* disponibilidad;
* crédito, opcionalmente;
* cambios desde la última consulta.

Si el precio cambió, la respuesta debe mostrar:

{
"requiresReconfirmation": true,
"changes": [
{
"lineId": "...",
"field": "unitPrice",
"previousValue": 125.50,
"currentValue": 128.50
}
]
}

El modelo debe mostrar el nuevo resumen al usuario y volver a solicitar confirmación.

Confirmación del usuario

La tool de envío debe incluir en su descripción una regla fuerte:

* Solo puede llamarse después de mostrar el resumen completo.
* El usuario debe confirmar explícitamente la generación del pedido.
* Agregar artículos, consultar el carrito o validarlo no constituye confirmación.
* Una respuesta ambigua no debe interpretarse como autorización.

Ejemplos válidos:

* “Confirmo el pedido”.
* “Sí, generar el pedido”.
* “Crear el pedido con esos datos”.

Ejemplos no válidos:

* “Está bien”.
* “Muéstrame el total”.
* “Valídalo”.
* “Agrega otro producto”.

Control de versión

El carrito debe tener un campo version.

La tool de envío debe recibir:

{
"expectedCartVersion": 5
}

Antes de generar el pedido debe comprobar:

cart.getVersion() == expectedCartVersion

Si no coincide, debe devolver un error estructurado, por ejemplo:

{
"status": "INVALID_REQUEST",
"code": "CART_VERSION_CONFLICT",
"message": "The cart changed after user confirmation.",
"currentCartVersion": 6
}

Prevención de pedidos duplicados

Como no habrá base de datos, quiero utilizar una referencia externa determinística.

Ejemplo:

MCP-{cartId}-{cartVersion}

Por ejemplo:

MCP-6e53bb42-8484-4bba-a550-91ef496a566c-5

Esta referencia debe enviarse a JDE en un campo consultable, por ejemplo:

* Customer PO;
* External Reference;
* campo reservado;
* campo personalizado;
* otra alternativa disponible en la operación JDE.

El flujo esperado es:

1. Generar la referencia externa.
2. Buscar si ya existe un pedido con esa referencia.
3. Si existe, devolver el pedido existente.
4. Si no existe, crear el pedido.
5. Guardar el número de pedido resultante dentro del carrito en memoria.
6. Si la tool se vuelve a ejecutar durante la misma sesión, devolver el mismo pedido.

Analiza las limitaciones reales de esta estrategia cuando el MCP se reinicia y explica qué debería soportar la operación JDE para que la idempotencia sea efectiva.

Creación del pedido JDE

No quiero que el modelo coordine operaciones de bajo nivel como:

crear encabezado
agregar línea 1
agregar línea 2
commit

La tool debe representar una única operación de negocio:

jde_submit_current_sales_cart

Internamente, un servicio Java debe encargarse de:

1. Verificar estado.
2. Verificar versión.
3. Generar la referencia externa.
4. Buscar un pedido existente.
5. Marcar el carrito como SUBMITTING.
6. Crear el encabezado.
7. Crear todas las líneas.
8. Confirmar la transacción.
9. Hacer rollback ante error.
10. Marcar el carrito como ORDER_CREATED.
11. Devolver:

* company;
* orderNumber;
* orderType;
* externalReference.

Si Atina ya ofrece una operación de alto nivel para crear un pedido completo, debe reutilizarse.

Salidas estructuradas

Mantén el estilo de mis tools actuales.

Ejemplo general:

{
"status": "OK",
"message": "Item added to cart.",
"cart": {
"cartId": "...",
"status": "OPEN",
"version": 3,
"customerId": 4242,
"currencyCode": "USD",
"lines": [],
"total": 0
}
}

La salida de envío podría ser:

{
"status": "OK",
"message": "Sales order created successfully.",
"cartId": "...",
"externalReference": "MCP-...",
"order": {
"company": "00001",
"orderNumber": 583921,
"orderType": "SO"
},
"recoveredFromExistingOrder": false
}

Errores esperados

Diseña códigos de error claros, por ejemplo:

CART_NOT_FOUND
CART_ACCESS_DENIED
CART_EXPIRED
CART_NOT_EDITABLE
CART_EMPTY
CART_VERSION_CONFLICT
CUSTOMER_MISMATCH
ITEM_NOT_FOUND
PRICE_NOT_FOUND
PRICE_CHANGED
INSUFFICIENT_AVAILABILITY
CURRENCY_NOT_RESOLVED
CREDIT_LIMIT_EXCEEDED
ORDER_ALREADY_CREATED
ORDER_SUBMISSION_FAILED

Quiero que se utilice el campo message para una explicación amigable y, si lo consideras apropiado, un campo adicional code.

Annotations sugeridas

Analiza las annotations, pero toma como referencia:

Crear carrito:
readOnlyHint: false
destructiveHint: false
idempotentHint: true
openWorldHint: false
Agregar producto:
readOnlyHint: false
destructiveHint: false
idempotentHint: false
openWorldHint: true
Consultar carrito:
readOnlyHint: true
destructiveHint: false
idempotentHint: true
openWorldHint: false
Validar carrito:
readOnlyHint: false
destructiveHint: false
idempotentHint: true o false, según implementación
openWorldHint: true
Enviar pedido:
readOnlyHint: false
destructiveHint: true
idempotentHint: true, solo si la referencia externa garantiza idempotencia
openWorldHint: true
Vaciar carrito:
readOnlyHint: false
destructiveHint: true
idempotentHint: true
openWorldHint: false

Explica cualquier cambio respecto de estas sugerencias.

Concurrencia

En esta primera etapa habrá una sola instancia del MCP.

Propón una solución simple para impedir que dos llamadas modifiquen simultáneamente el mismo carrito.

Puede utilizarse:

synchronized (cart) {
// operación
}

o una alternativa más limpia.

Explica:

* qué protege;
* qué no protege;
* por qué deja de ser suficiente con varias instancias;
* cómo se reemplazaría más adelante con Redis o locking distribuido.

Configuración

Propón propiedades configurables similares a:

cart:
expiration: PT2H
cleanup-delay: 300000
max-active-carts: 1000
check-availability-on-add: true
check-credit-on-submit: true
block-on-credit-limit: false

Entregable solicitado

Quiero que me entregues una propuesta concreta y aplicable sobre mi proyecto existente.

Organiza la respuesta en este orden:

1. Resumen de la arquitectura recomendada.
2. Decisiones principales.
3. Flujo conversacional completo.
4. Lista final de tools.
5. Descripción detallada de cada tool.
6. Input Schema y Output Schema de cada tool.
7. Annotations MCP recomendadas.
8. Modelo de dominio Java.
9. Interfaces y clases principales.
10. Repositorio en memoria.
11. Obtención del sessionId.
12. Validación del usuario autenticado.
13. Servicio de carrito.
14. Servicio de validación.
15. Servicio de creación del pedido.
16. Idempotencia contra JDE.
17. Concurrencia.
18. Expiración y limpieza.
19. Configuración application.yml.
20. Manejo de errores.
21. Casos de prueba.
22. Plan incremental de implementación.

No reescribas todo mi MCP Server.

Debes adaptar la propuesta a la arquitectura existente y señalar claramente:

* clases nuevas;
* clases existentes que deberían modificarse;
* métodos nuevos;
* dependencias nuevas, si existieran;
* cambios en las descripciones y schemas de las tools;
* supuestos que necesitas hacer.

Prioriza:

* simplicidad;
* bajo impacto;
* compatibilidad con las tools actuales;
* separación entre tools MCP y lógica de negocio;
* posibilidad de migrar a Redis posteriormente;
* seguridad;
* trazabilidad;
* prevención de pedidos duplicados.

Cuando falte algún detalle concreto de mi código, no inventes nombres o APIs. Indica el supuesto y muestra una interfaz o pseudocódigo adaptable.

Podés pegar después de este prompt las clases principales del MCP, especialmente donde defines las @McpTool, cómo obtienes el Mcp-Session-Id y cómo gestionas el usuario autenticado.