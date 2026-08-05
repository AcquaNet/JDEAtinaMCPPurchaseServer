package com.atina.jdeMCPServer.cart.model;

/**
 * Un campo de una línea del carrito cuyo valor cambió entre el momento en que
 * se agregó/actualizó y la última revalidación (jde_validate_current_sales_cart).
 * Cuando aparece al menos un CartChange, la respuesta marca
 * requiresReconfirmation=true y el carrito vuelve a OPEN.
 */
public record CartChange(String lineId, String field, String previousValue, String currentValue) {
}
