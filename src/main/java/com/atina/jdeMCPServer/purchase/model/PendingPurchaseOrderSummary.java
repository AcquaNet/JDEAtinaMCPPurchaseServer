package com.atina.jdeMCPServer.purchase.model;

import java.math.BigDecimal;

public record PendingPurchaseOrderSummary(
        String documentOrderTypeCode,
        Long documentOrderInvoiceNumber,
        String documentCompanyKeyOrderNo,
        String documentSuffix,
        String supplierName,
        String shipToName,
        BigDecimal amountToApprove,
        String currencyToApprove,
        Long daysOld,
        String dateRequested,
        String dateTransaction
) {
}
