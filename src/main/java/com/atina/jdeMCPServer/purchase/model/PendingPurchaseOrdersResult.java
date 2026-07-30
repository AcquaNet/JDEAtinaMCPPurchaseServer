package com.atina.jdeMCPServer.purchase.model;

import java.util.List;

/**
 * Salida estructurada de jde_list_pending_purchase_orders. pollAfterSeconds
 * es 0 salvo cuando status = IN_PROGRESS; orders es una lista vacia salvo
 * cuando status = OK (nunca null -- el SDK de MCP valida structuredContent
 * contra outputSchema y rechaza null en campos no declarados nullable).
 */
public record PendingPurchaseOrdersResult(
        ToolStatus status,
        String message,
        Integer pollAfterSeconds,
        List<PendingPurchaseOrderSummary> orders
) {
}
