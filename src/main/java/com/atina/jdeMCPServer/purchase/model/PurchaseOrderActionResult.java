package com.atina.jdeMCPServer.purchase.model;

/**
 * Salida estructurada de jde_approve_purchase_order / jde_reject_purchase_order.
 * action es "A" o "R" (mismo valor que espera processPurchaseOrderApproveReject).
 */
public record PurchaseOrderActionResult(
        ToolStatus status,
        String message,
        String action,
        String documentOrderTypeCode,
        Long documentOrderInvoiceNumber,
        String documentCompanyKeyOrderNo,
        String documentSuffix
) {
}
