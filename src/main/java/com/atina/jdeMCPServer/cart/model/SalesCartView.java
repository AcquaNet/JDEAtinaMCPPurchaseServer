package com.atina.jdeMCPServer.cart.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Vista serializable de un SalesCart para respuestas de tool -- campos nunca
 * null. Usada por todos los *Result del carrito.
 */
public record SalesCartView(
        String cartId,
        CartStatus status,
        long version,
        Integer customerId,
        String customerName,
        String businessUnit,
        String currencyCode,
        List<SalesCartLineView> lines,
        BigDecimal total,
        CreatedOrderRef createdOrder
) {
    /**
     * Placeholder para cuando no hay carrito activo (errorCode=CART_NOT_FOUND).
     * "status" no tiene un significado real acá -- CANCELLED se usa solo como
     * valor no-null; el errorCode/message del *Result son la fuente de verdad.
     */
    public static SalesCartView empty() {
        return new SalesCartView("", CartStatus.CANCELLED, 0L, 0, "", "", "",
                List.of(), BigDecimal.ZERO, CreatedOrderRef.empty());
    }

    public static SalesCartView from(SalesCart cart) {
        List<SalesCartLineView> lineViews = cart.lines().stream().map(SalesCartLineView::from).toList();
        BigDecimal total = cart.lines().stream()
                .map(SalesCartLine::extendedPrice)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SalesCartView(
                cart.cartId(),
                cart.status(),
                cart.version(),
                cart.customerId() != null ? cart.customerId() : 0,
                cart.customerName() != null ? cart.customerName() : "",
                cart.businessUnit() != null ? cart.businessUnit() : "",
                cart.currencyCode() != null ? cart.currencyCode() : "",
                lineViews,
                total,
                cart.createdOrder() != null ? cart.createdOrder() : CreatedOrderRef.empty());
    }
}
