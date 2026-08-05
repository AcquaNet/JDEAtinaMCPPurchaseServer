package com.atina.jdeMCPServer.cart.services;

import com.atina.jdeMCPServer.cart.model.CartChange;
import com.atina.jdeMCPServer.cart.model.SalesCart;

import java.util.List;

/** Resultado interno de SalesCartService.validateCart -- traducido a CartValidationResult por la tool. */
public record CartValidationOutcome(SalesCart cart, boolean requiresReconfirmation, List<CartChange> changes) {
}
