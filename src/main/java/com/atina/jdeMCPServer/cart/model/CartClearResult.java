package com.atina.jdeMCPServer.cart.model;

import com.atina.jdeMCPServer.salesorder.model.ToolStatus;

/** Salida estructurada de jde_clear_current_sales_cart. */
public record CartClearResult(ToolStatus status, String errorCode, String message, SalesCartView cart) {
}
