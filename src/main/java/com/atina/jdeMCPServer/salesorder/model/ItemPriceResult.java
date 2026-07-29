package com.atina.jdeMCPServer.salesorder.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Salida estructurada de jde_get_item_price. availability es una lista
 * vacia salvo cuando se pidió disponibilidad (returnAvailability = "Y") y
 * status = OK (nunca null -- el SDK de MCP valida structuredContent contra
 * outputSchema y rechaza null en campos no declarados nullable).
 */
public record ItemPriceResult(
        ToolStatus status,
        String message,
        Integer itemId,
        Integer customerId,
        String businessUnit,
        String currencyCode,
        BigDecimal unitPrice,
        BigDecimal extendedPrice,
        List<WarehouseAvailability> availability
) {
}
