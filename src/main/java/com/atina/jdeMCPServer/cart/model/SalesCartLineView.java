package com.atina.jdeMCPServer.cart.model;

import java.math.BigDecimal;

/**
 * Vista de una SalesCartLine para respuestas de tool -- campos nunca null (el
 * SDK MCP valida structuredContent contra el outputSchema generado y rechaza
 * null en campos no declarados nullable, ver convención del proyecto).
 */
public record SalesCartLineView(
        String lineId,
        Integer itemId,
        String itemCatalog,
        String description,
        BigDecimal quantity,
        String unitOfMeasure,
        String businessUnit,
        BigDecimal unitPrice,
        BigDecimal extendedPrice,
        String currencyCode,
        BigDecimal availableQuantity
) {
    public static SalesCartLineView from(SalesCartLine line) {
        return new SalesCartLineView(
                line.lineId(),
                line.itemId() != null ? line.itemId() : 0,
                line.itemCatalog() != null ? line.itemCatalog() : "",
                line.description() != null ? line.description() : "",
                line.quantity() != null ? line.quantity() : BigDecimal.ZERO,
                line.unitOfMeasure() != null ? line.unitOfMeasure() : "",
                line.businessUnit() != null ? line.businessUnit() : "",
                line.unitPrice() != null ? line.unitPrice() : BigDecimal.ZERO,
                line.extendedPrice() != null ? line.extendedPrice() : BigDecimal.ZERO,
                line.currencyCode() != null ? line.currencyCode() : "",
                line.availableQuantity() != null ? line.availableQuantity() : BigDecimal.ZERO);
    }
}
