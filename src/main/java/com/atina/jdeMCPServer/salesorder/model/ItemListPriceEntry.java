package com.atina.jdeMCPServer.salesorder.model;

import java.math.BigDecimal;

public record ItemListPriceEntry(
        String businessUnit,
        Integer itemId,
        String itemCatalog,
        String itemDescription,
        BigDecimal priceList,
        String currencyCode,
        String unitOfMeasureCode,
        String dateEffective,
        String dateExpiration
) {
}
