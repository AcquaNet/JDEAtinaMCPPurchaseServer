package com.atina.jdeMCPServer.salesorder.model;

import java.math.BigDecimal;

/**
 * Salida estructurada de jde_get_customer_credit_info. pollAfterSeconds es 0
 * salvo cuando status = IN_PROGRESS; creditLimit/totalExposure/
 * creditHoldExempt/taxId nunca son null -- 0/false/"" cuando status != OK (el
 * SDK de MCP valida structuredContent contra outputSchema y rechaza null en
 * campos no declarados nullable).
 */
public record CustomerCreditInfoResult(
        ToolStatus status,
        String message,
        Integer pollAfterSeconds,
        Integer customerNumber,
        BigDecimal creditLimit,
        BigDecimal totalExposure,
        Boolean creditHoldExempt,
        String taxId
) {
}
