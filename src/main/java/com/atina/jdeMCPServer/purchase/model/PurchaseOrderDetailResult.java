package com.atina.jdeMCPServer.purchase.model;

import java.util.List;
import java.util.Map;

/**
 * Salida estructurada de jde_get_purchase_order_detail. header/lines nunca
 * son null -- Map.of()/List.of() cuando status != OK (el SDK de MCP valida
 * structuredContent contra outputSchema y rechaza null en campos no
 * declarados nullable). Ver PurchaseOrderDetail sobre por qué header/lines
 * no están tipados campo por campo todavía.
 */
public record PurchaseOrderDetailResult(
        ToolStatus status,
        String message,
        String documentOrderTypeCode,
        Long documentOrderInvoiceNumber,
        String documentCompanyKeyOrderNo,
        String documentSuffix,
        Map<String, Object> header,
        List<Map<String, Object>> lines
) {
}
