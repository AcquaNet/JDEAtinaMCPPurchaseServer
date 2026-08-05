package com.atina.jdeMCPServer.cart.model;

import com.atina.jdeMCPServer.salesorder.model.ToolStatus;

/** Salida estructurada de jde_create_current_sales_cart. */
public record CartCreateResult(ToolStatus status, String errorCode, String message, SalesCartView cart) {
}
