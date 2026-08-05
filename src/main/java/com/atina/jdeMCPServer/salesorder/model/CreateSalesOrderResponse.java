package com.atina.jdeMCPServer.salesorder.model;

import java.math.BigDecimal;

/**
 * Resultado de processSalesOrderV5, parseado según una respuesta real
 * confirmada (ver .claude/generaciondepedido.md, sección "Respuesta real
 * confirmada"): el número de pedido es salesOrderKey.documentNumber (NO
 * documentOrderInvoiceNumber, que es un campo de purchase orders, un módulo
 * distinto); la moneda está en header.financial.currencyCode (no en
 * currencyCodeTo, que es solo de request).
 */
public record CreateSalesOrderResponse(
        String documentCompany,
        String orderNumber,
        String orderType,
        String currencyCode,
        BigDecimal totalAmount,
        String attachmentText
) {
}
