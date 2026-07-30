package com.atina.jdeMCPServer.salesorder.model;

import java.util.List;

/**
 * Salida estructurada de jde_get_item_list_price. pollAfterSeconds es 0 salvo
 * cuando status = IN_PROGRESS; prices es una lista vacia salvo cuando
 * status = OK (nunca null -- el SDK de MCP valida structuredContent contra
 * outputSchema y rechaza null en campos no declarados nullable). Puede tener
 * mas de una entrada cuando no se informa businessUnit (un precio por almacen).
 */
public record ItemListPriceResult(
        ToolStatus status,
        String message,
        Integer pollAfterSeconds,
        List<ItemListPriceEntry> prices
) {
}
