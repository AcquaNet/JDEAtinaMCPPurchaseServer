package com.atina.jdeMCPServer.purchase.model;

import java.util.List;
import java.util.Map;

/**
 * Detalle de una orden de compra devuelto por JdePurchaseOrderClient. No es
 * un tipo MCP -- no lleva status/message, eso lo agrega la tool al armar
 * PurchaseOrderDetailResult.
 *
 * header/lines quedan sin tipar (Map genérico) a propósito: a diferencia de
 * las demás respuestas estructuradas de este proyecto, no hay todavía una
 * respuesta real capturada de getPurchaseOrderV2 /
 * getPurchaseOrderDetailForApprover que confirme los nombres exactos de cada
 * campo -- inventar un record con nombres de campo no verificados sería peor
 * que dejarlo genérico. Cuando se confirme contra el Gateway real, tipar
 * header/lines igual que el resto de las respuestas del proyecto.
 */
public record PurchaseOrderDetail(
        Map<String, Object> header,
        List<Map<String, Object>> lines
) {
    public static PurchaseOrderDetail empty() {
        return new PurchaseOrderDetail(Map.of(), List.of());
    }
}
