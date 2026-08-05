package com.atina.jdeMCPServer.cart.model;

import com.atina.jdeMCPServer.salesorder.model.ToolStatus;

/** Salida estructurada de jde_add_item_to_current_sales_cart / jde_update_current_sales_cart_item / jde_remove_current_sales_cart_item. */
public record CartLineResult(ToolStatus status, String errorCode, String message, SalesCartView cart) {
}
