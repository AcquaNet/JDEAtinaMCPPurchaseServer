package com.atina.jdeMCPServer.cart.model;

import com.atina.jdeMCPServer.salesorder.model.ToolStatus;

/** Salida estructurada de jde_get_current_sales_cart. */
public record CartResult(ToolStatus status, String errorCode, String message, SalesCartView cart) {
}
