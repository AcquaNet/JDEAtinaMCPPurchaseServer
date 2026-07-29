package com.atina.jdeMCPServer.salesorder.model;

import java.math.BigDecimal;

public record WarehouseAvailability(
        String warehouseCode,
        String warehouseName,
        BigDecimal quantityAvailable
) {
}
