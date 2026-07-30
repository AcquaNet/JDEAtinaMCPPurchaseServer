package com.atina.jdeMCPServer.salesorder.model;

/**
 * Detalle de cliente devuelto por JdeSalesOrderClient. No es un tipo MCP --
 * no lleva status/message, eso lo agrega la tool al armar CustomerDetailResult.
 */
public record CustomerDetail(
        Integer addressBookNumber,
        String name,
        String taxId,
        String company,
        String currencyCode,
        String languageCode,
        CustomerAddress address,
        CustomerCreditInfo credit
) {
    public static CustomerDetail empty() {
        return new CustomerDetail(0, "", "", "", "", "", CustomerAddress.empty(), CustomerCreditInfo.empty());
    }
}
