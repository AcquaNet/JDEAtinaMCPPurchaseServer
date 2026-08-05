





Agrego detalle de API que genera pedidos en JDE:

curl --location 'https://jde-atina-gateway.dock-ia.com/v1/operations/execute' \
--header 'Token: null' \
--header 'Accept: application/json' \
--header 'Content-Type: application/json' \
--header 'Accept-Encoding: gzip, deflate, br' \
--header 'TransactionId: 0' \
--header 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiIwNDgyNzUiLCJpYXQiOjE3ODU4NzMzMzAsInN1YiI6IlN1YmplY3QiLCJpc3MiOiJJc3N1ZSIsInVzZXIiOiJNVUxFU09GVCIsImVudmlyb25tZW50IjoiSkRWOTIwIiwicm9sZSI6IipBTEwiLCJzZXNzaW9uSWQiOi0xOTI3NDk5MzAyLCJ3c0Nvbm5lY3Rpb24iOnRydWUsInVzZVZhdWx0Ijp0cnVlLCJhZGRyZXNzQm9va051bWJlciI6IjkyNDU1ODkwIiwidG9rZW5FeHBpcmF0aW9uIjowfQ.k4rOpImDsDwqh9KWrPCODHoxOsRZSU3_nhCgWN-Pb68' \
--data '{
"operacionKey": "oracle.e1.bssv.JP420000.SalesOrderManager.processSalesOrderV5",
"listaDeValores": [
{
"header": {
"businessUnit": "          30",
"invoicedTo": {
"entityId": 4242
},
"attachmentText": "Order Number",
"dateRequested": "2026-08-01",
"deliverTo": {
"entityId": 4242
},
"shipTo": {
"customer": {
"entityId": 4242
}
},
"salesOrderKey": {
"documentTypeCode": "SO",
"documentCompany": "00001"
},
"dateOrdered": "2026-07-06",
"company": "0001",
"currencyCodeTo": "USD",
"processing": {
"actionType": "A",
"processingVersion": "ZJDE0001"
},
"detail": [
{
"businessUnit": "          30",
"quantityOrdered": 1,
"lineTypeCode": "S",
"reference": "REF1",
"product": {
"item": {
"itemProduct": "210"
}
},
"processing": {
"actionType": "A"
}
},
{
"businessUnit": "          30",
"quantityOrdered": 2,
"lineTypeCode": "S",
"reference": "REF1",
"product": {
"item": {
"itemProduct": "220"
}
},
"processing": {
"actionType": "A"
}
},
{
"businessUnit": "          30",
"quantityOrdered": 2,
"lineTypeCode": "S",
"reference": "REF1",
"product": {
"item": {
"itemProduct": "230"
}
},
"processing": {
"actionType": "A"
}
}
]

            }
        } 
],
"connectorName": "WS"
}'

Este API ejecuta el Business Service de JD Edwards:

oracle.e1.bssv.JP420000.SalesOrderManager.processSalesOrderV5

Su objetivo es crear un pedido de venta completo en JDE, incluyendo la cabecera y todas sus líneas, mediante una única llamada.

Estructura general

{
"operacionKey": "...processSalesOrderV5",
"listaDeValores": [
{
"header": {
"...": "...",
"detail": []
}
}
],
"connectorName": "WS"
}

operacionKey

Identifica la operación de JDE que se va a ejecutar:

oracle.e1.bssv.JP420000.SalesOrderManager.processSalesOrderV5

Corresponde al Business Service estándar JP420000, responsable del procesamiento de pedidos de venta.

connectorName

"connectorName": "WS"

Indica que la operación se ejecuta mediante el conector de Web Services de JDE.

listaDeValores

Contiene los parámetros que se envían a la operación. En este caso contiene un único objeto llamado header, que representa el pedido completo.

⸻

Cabecera del pedido

Centro de negocio

"businessUnit": "          30"

Es la sucursal, almacén o Business Unit asociada al pedido.

⸻

Cliente facturado

"invoicedTo": {
"entityId": 4242
}

Indica el cliente al que se emitirá la factura.


⸻

Texto adjunto

"attachmentText": "Order Number"

Texto adicional asociado al pedido.

Dependiendo de la configuración de JDE, puede utilizarse como comentario, referencia o texto adjunto del pedido.

También podría utilizarse para guardar una referencia externa del MCP, por ejemplo:

MCP-<cartId>-<version>

Sin embargo, antes de usarlo para idempotencia hay que confirmar que este campo pueda buscarse posteriormente de forma confiable en JDE.

⸻

Fecha solicitada

"dateRequested": "2026-08-01"

Es la fecha en la que el cliente solicita recibir o disponer de los productos.

En el ejemplo, la fecha es 1 de agosto de 2026, que ya es anterior a la fecha actual del 4 de agosto de 2026. Para un pedido nuevo normalmente debería utilizarse una fecha actual o futura, salvo que JDE admita pedidos retroactivos.

⸻

Dirección o cliente de entrega

"deliverTo": {
"entityId": 4242
}

Representa la entidad o dirección donde se entregará el pedido.

En este ejemplo coincide con el cliente facturado.

⸻

Ship-to

"shipTo": {
"customer": {
"entityId": 4242
}
}

Indica el cliente o dirección de envío.

Aunque deliverTo, shipTo e invoicedTo contienen el mismo entityId en el ejemplo, conceptualmente pueden representar entidades diferentes:

invoicedTo → cliente facturado
shipTo     → cliente o dirección de envío
deliverTo  → destinatario final

⸻

Clave del pedido

"salesOrderKey": {
"documentTypeCode": "SO",
"documentCompany": "00001"
}

Define los datos principales de identificación del documento.

documentTypeCode

"documentTypeCode": "SO"

Tipo de documento JDE.

SO normalmente representa un pedido de venta estándar.

documentCompany

"documentCompany": "00001"

Compañía documental del pedido.

Debe mantenerse con el formato exacto esperado por JDE, incluyendo ceros a la izquierda.

⸻

Fecha del pedido

"dateOrdered": "2026-07-06"

Es la fecha de creación o fecha comercial del pedido.

En este ejemplo es 6 de julio de 2026, también anterior a la fecha actual. Para pedidos generados desde el MCP normalmente debería enviarse la fecha actual, salvo que el usuario haya pedido explícitamente una fecha histórica.

⸻

Compañía

"company": "0001"

Es la compañía operativa o compañía asociada al pedido.

Hay que prestar atención porque en la misma solicitud aparecen:

company:         "0001"
documentCompany: "00001"

Son campos distintos, pero conviene confirmar que esta diferencia de longitud sea correcta para la configuración del entorno JDE.

No deberían normalizarse automáticamente como si fueran el mismo campo.

⸻

Moneda

"currencyCodeTo": "USD"

Indica la moneda del pedido.

En el flujo del carrito, esta moneda debería venir de:

1. La moneda indicada explícitamente por el usuario; o
2. La moneda de transacción configurada para el cliente.

No conviene asumir USD automáticamente.

⸻

Procesamiento de cabecera

"processing": {
"actionType": "A",
"processingVersion": "ZJDE0001"
}

actionType

"actionType": "A"

Indica que la operación debe agregar o crear el pedido.

Conceptualmente:

A = Add

processingVersion

"processingVersion": "ZJDE0001"

Es la versión de procesamiento de JDE que controla reglas y valores predeterminados de la operación.

Puede determinar aspectos como:

* Estados iniciales.
* Validaciones.
* Tipo de línea.
* Reglas de precios.
* Almacén predeterminado.
* Procesamiento de inventario.
* Validación de crédito.

La versión debería configurarse en el servidor y no ser inventada por el modelo.

⸻

Líneas del pedido

La propiedad:

"detail": []

contiene todos los artículos del pedido.

En este ejemplo hay tres líneas:

Producto  Cantidad  Business Unit Tipo de línea
210 1 30  S
220 2 30  S
230 2 30  S

Cada elemento del carrito se transforma en una entrada dentro de detail.

⸻

Business Unit de la línea

"businessUnit": "          30"

Representa el almacén o sucursal desde donde se procesará la línea.

Puede coincidir con la cabecera, aunque cada línea podría utilizar un almacén diferente si el proceso de negocio lo permite.

⸻

Cantidad pedida

"quantityOrdered": 1

Cantidad solicitada del producto.

En las otras líneas se envía:

"quantityOrdered": 2

La cantidad debe ser validada antes de generar el pedido:

* Debe ser mayor que cero.
* Debe respetar la unidad de medida.
* Puede requerir validación de stock.
* Puede afectar el precio específico del cliente.

⸻

Tipo de línea

"lineTypeCode": "S"

Código de tipo de línea de JDE.

Normalmente S identifica una línea de inventario estándar, aunque su comportamiento exacto depende de la configuración del entorno.

El modelo no debería decidir este valor libremente. Lo ideal es configurarlo en el MCP Server.

⸻

Referencia de línea

"reference": "REF1"

Es una referencia asociada a la línea.

Actualmente todas las líneas utilizan el mismo valor:

REF1

Podría utilizarse para guardar:

* Una referencia del carrito.
* Un identificador de línea.
* Una referencia externa.
* Un código de origen.

Por ejemplo:

MCP-<cartId>-L1
MCP-<cartId>-L2

Antes de usarlo para evitar duplicados, hay que verificar si JDE permite consultar pedidos por este campo.

⸻

Identificación del producto

"product": {
"item": {
"itemProduct": "210"
}
}

Identifica el artículo de JDE.

En las tres líneas se envían:

210
220
230

itemProduct debe contener el identificador que espera la operación processSalesOrderV5.

Es importante verificar si corresponde a:

* Segundo número de artículo.
* Código corto.
* Código de catálogo.
* Otro identificador configurado.

En tus tools actuales manejas:

itemCatalog
itemId

Por tanto, antes de construir el pedido necesitas definir claramente cómo se transforma el producto seleccionado en:

"itemProduct": "210"

No conviene asumir que itemCatalog, itemId e itemProduct son siempre equivalentes.

⸻

Procesamiento de línea

"processing": {
"actionType": "A"
}

Indica que la línea debe agregarse al pedido.

Cada línea tiene su propia acción, independiente de la acción de cabecera.

⸻

Flujo desde el carrito

El carrito podría transformarse en esta solicitud de la siguiente manera:

Carrito
│
├── customerId
│     ├── invoicedTo.entityId
│     ├── deliverTo.entityId
│     └── shipTo.customer.entityId
│
├── businessUnit
│     ├── header.businessUnit
│     └── detail[].businessUnit
│
├── currencyCode
│     └── currencyCodeTo
│
├── requestedDate
│     └── dateRequested
│
└── lines[]
├── quantity → quantityOrdered
├── itemProduct → product.item.itemProduct
├── lineType → lineTypeCode
└── reference → reference

Ejemplo conceptual:

SalesOrderRequest request = new SalesOrderRequest();
request.setCustomerId(cart.getCustomerId());
request.setBusinessUnit(cart.getBusinessUnit());
request.setCurrencyCode(cart.getCurrencyCode());
request.setRequestedDate(cart.getRequestedDate());
for (SalesCartLine cartLine : cart.getLines()) {
request.addDetail(
cartLine.getItemProduct(),
cartLine.getQuantity(),
cartLine.getBusinessUnit()
);
}

⸻

Validaciones antes de llamar al API

Antes de ejecutar processSalesOrderV5, el MCP debería comprobar:

1. Que exista un carrito activo.
2. Que el carrito pertenezca al usuario autenticado.
3. Que tenga al menos una línea.
4. Que el cliente esté identificado.
5. Que la moneda esté resuelta.
6. Que cada línea tenga producto y cantidad.
7. Que los precios hayan sido recalculados.
8. Que la disponibilidad haya sido comprobada, si corresponde.
9. Que el usuario haya confirmado explícitamente.
10. Que la versión del carrito no haya cambiado.
11. Que no exista ya un pedido con la misma referencia externa.

⸻

Referencia externa para idempotencia

Para impedir pedidos duplicados, podría generarse:

MCP-{cartId}-{cartVersion}

Por ejemplo:

MCP-6e53bb42-8484-4bba-a550-91ef496a566c-5

Esta referencia podría enviarse inicialmente en:

"attachmentText": "MCP-6e53bb42-8484-4bba-a550-91ef496a566c-5"

o, si está soportado:

"reference": "MCP-6e53bb42-L1"

Pero la idempotencia solo será realmente efectiva si existe una operación que permita:

Buscar un pedido de JDE por esa referencia externa

El flujo debería ser:

Generar referencia
↓
Buscar pedido existente
↓
¿Existe?
├── Sí → devolver pedido existente
└── No → ejecutar processSalesOrderV5

⸻

Resumen funcional

Esta llamada crea un pedido de venta SO para el cliente 4242, en la compañía documental 00001, la compañía 0001, el Business Unit 30 y la moneda USD.

El pedido contiene:

* Una unidad del producto 210.
* Dos unidades del producto 220.
* Dos unidades del producto 230.

La cabecera y las líneas utilizan actionType = "A", indicando que deben agregarse. La versión de procesamiento utilizada es ZJDE0001.

Desde el MCP, esta operación debería ejecutarse únicamente al confirmar el carrito. El modelo no debería construir directamente esta estructura: un servicio Java interno debe transformar el carrito validado en el payload de processSalesOrderV5, ejecutar la operación y devolver el número de pedido creado.

⸻

Respuesta real confirmada (ejecución de prueba contra el ambiente de dev)

A diferencia del request, `listaDeValores` en la respuesta es un objeto directo (no un array) con una única clave `header` adentro -- mismo criterio ya usado por parseListaDeValores en JdeSalesOrderClient.

```json
{
    "jwtToken": "...",
    "listaDeValores": {
        "e1MessageList": { "E1Messages": null, "messagesAsString": "" },
        "header": {
            "actionType": "A",
            "amountTotalOrderDomestic": 2308.2,
            "amountTotalOrderForeign": 0,
            "attachmentText": "Order Number",
            "businessUnit": "          30",
            "dates": { "orderDate": "2026-08-03T00:00:00Z" },
            "detail": [
                {
                    "documentLineNumber": 0,
                    "financial": {
                        "priceExtendedDomestic": 718.2,
                        "priceUnitDomestic": 718.2,
                        "unitOfMeasureCodePricing": "EA"
                    },
                    "product": {
                        "item": {
                            "itemCatalog": "210                      ",
                            "itemId": 60011,
                            "itemProduct": "210"
                        },
                        "lineTypeCode": "S",
                        "statusCodeLast": "520",
                        "statusCodeNext": "540"
                    },
                    "quantity": {
                        "quantityOrdered": 1,
                        "quantityShippable": 1,
                        "unitOfMeasureCodeTransaction": "EA"
                    },
                    "shipTo": { "entityId": 4242 }
                }
            ],
            "financial": { "currencyCode": "USD", "paymentTerms": "   " },
            "holdOrderCode": "  ",
            "salesOrderKey": {
                "documentCompany": "00001",
                "documentNumber": 3278,
                "documentTypeCode": "SO"
            },
            "shipTo": {
                "addressLine1": "400 Broadland Road NW                   ",
                "city": "Atlanta                  ",
                "countryCode": "US ",
                "customer": { "entityId": 4242, "entityTaxId": "20924558904         " },
                "mailingName": "Capital System Inc                      ",
                "postalCode": "30342       ",
                "stateCode": "GA "
            }
        }
    },
    "sessionId": "-1794289409"
}
```

Puntos confirmados que reemplazan los supuestos anteriores:

* El número de pedido real es `header.salesOrderKey.documentNumber` (entero, ej. `3278`) -- NO `documentOrderInvoiceNumber` (ese nombre era una analogía incorrecta con la clave compuesta de purchase orders, que es un módulo distinto).
* `header.salesOrderKey.documentTypeCode` / `documentCompany` confirman el tipo de documento y la compañía documental, ecos exactos de lo enviado.
* La moneda de la respuesta vive en `header.financial.currencyCode`, NO en `header.currencyCodeTo` (ese campo es solo de request).
* `header.attachmentText` se devuelve exactamente como se envió -- confirma que el campo se persiste y se puede leer de vuelta en la misma respuesta de creación. Esto NO confirma todavía que sea buscable después vía una operación de consulta separada (sigue siendo un supuesto abierto para la idempotencia real cross-reinicio, ver carrito.md).
* `header.amountTotalOrderDomestic` da el total del pedido, útil para mostrar en la confirmación al usuario.
* `header.detail[].product.item` trae `itemId`, `itemCatalog` (con padding a la derecha) e `itemProduct` ecos de la línea enviada, más `financial.priceUnitDomestic`/`priceExtendedDomestic` con el precio real aplicado por JDE.
* No hay ningún campo `company` (el "0001" corto) en el header de respuesta -- solo `documentCompany` ("00001"). El "company" corto sigue siendo responsabilidad de quien arma el payload (valor de configuración, no algo que JDE devuelva para confirmar).

Sobre el tiempo de respuesta: si `processSalesOrderV5` resulta lento en uso real, usar el mismo mecanismo async con kill-switch (`jde.<tool>.async.enabled` + `LongRunningTaskRegistry`) ya implementado para las demás tools de sales order, en vez de dejarlo forzosamente síncrono.














