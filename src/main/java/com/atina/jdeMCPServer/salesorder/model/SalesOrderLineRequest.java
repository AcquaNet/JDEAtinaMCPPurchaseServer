package com.atina.jdeMCPServer.salesorder.model;

import java.math.BigDecimal;

/**
 * Una línea de detail[] para la operación BSSV processSalesOrderV5 (ver
 * JdeSalesOrderClient.createSalesOrder y .claude/generaciondepedido.md).
 * itemProduct es el identificador que esta operación espera para el
 * producto -- distinto de itemId/itemCatalog usados por las operaciones de
 * consulta de precio; quien arma esta línea (SalesCartService) decide qué
 * valor de la línea del carrito usar como itemProduct.
 */
public record SalesOrderLineRequest(
        String businessUnit,
        BigDecimal quantityOrdered,
        String lineTypeCode,
        String reference,
        String itemProduct
) {
}
