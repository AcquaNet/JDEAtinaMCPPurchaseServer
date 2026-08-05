package com.atina.jdeMCPServer.cart.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Línea de un SalesCart. itemId/itemCatalog siguen la misma convención que
 * jde_get_item_price (JdeItemTools): itemCatalog es el identificador
 * preferido, itemId el fallback -- al menos uno de los dos debe venir
 * informado (lo valida SalesCartService antes de construir la línea).
 */
public record SalesCartLine(
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
        BigDecimal availableQuantity,
        Instant priceCalculatedAt
) {
    public SalesCartLine withRecalculatedPrice(BigDecimal newUnitPrice, BigDecimal newExtendedPrice,
                                                BigDecimal newAvailableQuantity, Instant now) {
        return new SalesCartLine(lineId, itemId, itemCatalog, description, quantity, unitOfMeasure,
                businessUnit, newUnitPrice, newExtendedPrice, currencyCode, newAvailableQuantity, now);
    }

    public SalesCartLine withQuantity(BigDecimal newQuantity, String newUnitOfMeasure) {
        return new SalesCartLine(lineId, itemId, itemCatalog, description,
                newQuantity != null ? newQuantity : quantity,
                newUnitOfMeasure != null ? newUnitOfMeasure : unitOfMeasure,
                businessUnit, unitPrice, extendedPrice, currencyCode, availableQuantity, priceCalculatedAt);
    }
}
