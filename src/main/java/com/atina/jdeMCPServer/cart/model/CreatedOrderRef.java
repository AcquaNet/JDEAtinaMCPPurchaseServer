package com.atina.jdeMCPServer.cart.model;

/**
 * Referencia al pedido de venta creado en JDE a partir de un carrito. Vive
 * dentro de SalesCart una vez que su status pasa a ORDER_CREATED -- es lo que
 * permite que un reintento de jde_submit_current_sales_cart en la misma
 * sesión devuelva el mismo pedido sin volver a llamar al Gateway (ver
 * limitaciones de esta estrategia sin persistencia en el javadoc de
 * SalesCartService.submitCart).
 */
public record CreatedOrderRef(
        String company,
        String orderNumber,
        String orderType,
        String externalReference,
        boolean recoveredFromExistingOrder
) {
    public static CreatedOrderRef empty() {
        return new CreatedOrderRef("", "", "", "", false);
    }
}
