package com.atina.jdeMCPServer.cart.model;

import com.atina.jdeMCPServer.salesorder.model.ToolStatus;

/**
 * Salida estructurada de jde_submit_current_sales_cart. pollAfterSeconds es 0
 * salvo status=IN_PROGRESS (async habilitado y la creación del pedido sigue
 * en curso -- volver a llamar con el mismo expectedCartVersion y
 * confirm=true). recoveredFromExistingOrder=true cuando el carrito ya estaba
 * ORDER_CREATED y se devolvió el pedido existente sin llamar de nuevo al
 * Gateway.
 */
public record CartSubmitResult(
        ToolStatus status,
        String errorCode,
        String message,
        Integer pollAfterSeconds,
        String company,
        String orderNumber,
        String orderType,
        String externalReference,
        boolean recoveredFromExistingOrder,
        Long currentCartVersion,
        SalesCartView cart
) {
}
