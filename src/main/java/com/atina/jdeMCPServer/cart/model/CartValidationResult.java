package com.atina.jdeMCPServer.cart.model;

import com.atina.jdeMCPServer.salesorder.model.ToolStatus;

import java.util.List;

/** Salida estructurada de jde_validate_current_sales_cart. changes es [] salvo cuando requiresReconfirmation=true. */
public record CartValidationResult(
        ToolStatus status,
        String errorCode,
        String message,
        boolean requiresReconfirmation,
        List<CartChange> changes,
        SalesCartView cart
) {
}
