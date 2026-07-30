package com.atina.jdeMCPServer.salesorder.model;

import java.math.BigDecimal;

/**
 * Crédito y exposición financiera de un cliente, devuelto por
 * JdeSalesOrderClient. No es un tipo MCP -- no lleva status/message, eso lo
 * agrega la tool al armar CustomerCreditInfoResult.
 */
public record CustomerCreditSummary(
        BigDecimal creditLimit,
        BigDecimal totalExposure,
        Boolean creditHoldExempt,
        String taxId
) {
    public static CustomerCreditSummary empty() {
        return new CustomerCreditSummary(BigDecimal.ZERO, BigDecimal.ZERO, false, "");
    }
}
