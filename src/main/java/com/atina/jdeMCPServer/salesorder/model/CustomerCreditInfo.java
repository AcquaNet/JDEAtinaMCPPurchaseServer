package com.atina.jdeMCPServer.salesorder.model;

import java.math.BigDecimal;

public record CustomerCreditInfo(
        BigDecimal creditLimit,
        BigDecimal openAmount,
        BigDecimal dueAmount,
        String creditManagerCode,
        String creditCheckLevelCode,
        String holdCode
) {
    public static CustomerCreditInfo empty() {
        return new CustomerCreditInfo(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "", "", "");
    }
}
