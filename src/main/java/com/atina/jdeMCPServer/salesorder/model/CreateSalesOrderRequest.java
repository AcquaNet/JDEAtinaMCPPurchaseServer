package com.atina.jdeMCPServer.salesorder.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Datos ya resueltos para armar el payload de processSalesOrderV5 (ver
 * JdeSalesOrderClient.createSalesOrder). company/documentCompany son campos
 * DISTINTOS con distinto padding (ver .claude/generaciondepedido.md) -- no
 * normalizar como si fueran el mismo campo.
 */
public record CreateSalesOrderRequest(
        String businessUnit,
        int invoicedToEntityId,
        int deliverToEntityId,
        int shipToEntityId,
        String attachmentText,
        LocalDate dateRequested,
        LocalDate dateOrdered,
        String company,
        String documentCompany,
        String currencyCodeTo,
        String documentTypeCode,
        String processingVersion,
        String actionType,
        List<SalesOrderLineRequest> lines
) {
}
