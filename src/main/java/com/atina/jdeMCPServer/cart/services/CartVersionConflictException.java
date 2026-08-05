package com.atina.jdeMCPServer.cart.services;

import com.atina.jdeMCPServer.cart.model.CartErrorCodes;

/**
 * El carrito cambió (otra mutación lo incrementó) entre que el caller obtuvo
 * su version y llamó a jde_submit_current_sales_cart. Distinto de una
 * CartOperationException genérica porque el *Result necesita mostrar
 * currentCartVersion además de errorCode/message.
 */
public class CartVersionConflictException extends CartOperationException {

    private final long currentVersion;

    public CartVersionConflictException(long currentVersion) {
        super(CartErrorCodes.CART_VERSION_CONFLICT,
                "The cart changed after this version was last seen (currentCartVersion=" + currentVersion
                        + "). Call jde_get_current_sales_cart or jde_validate_current_sales_cart to see the "
                        + "latest state, confirm again with the user, and retry with the current version.");
        this.currentVersion = currentVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
