package com.atina.jdeMCPServer.salesorder.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Precio (y disponibilidad opcional) devuelto por JdeSalesOrderClient. No es
 * un tipo MCP -- no lleva status/message, eso lo agrega la tool al armar
 * ItemPriceResult. availability es una lista vacia (nunca null) cuando no
 * se pidió disponibilidad.
 */
public record PriceQuote(
        BigDecimal unitPrice,
        BigDecimal extendedPrice,
        List<WarehouseAvailability> availability
) {
}
