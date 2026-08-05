package com.atina.jdeMCPServer.cart.services;

/**
 * Error de negocio del carrito (ver CartErrorCodes), lanzado por
 * SalesCartService y capturado por JdeSalesCartTools para armar el *Result
 * correspondiente (ToolStatus.INVALID_REQUEST/FAILED + errorCode + message).
 */
public class CartOperationException extends RuntimeException {

    private final String errorCode;

    public CartOperationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
