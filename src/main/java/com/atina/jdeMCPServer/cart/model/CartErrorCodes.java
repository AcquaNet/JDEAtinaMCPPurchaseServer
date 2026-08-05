package com.atina.jdeMCPServer.cart.model;

/**
 * Códigos de negocio devueltos en el campo errorCode de los *Result del
 * carrito, además de ToolStatus/message. No se modelan como valores de un
 * enum porque ToolStatus (salesorder.model.ToolStatus) es compartido con
 * otras tools de sales order que no necesitan estos códigos.
 */
public final class CartErrorCodes {

    public static final String NONE = "";

    public static final String CART_NOT_FOUND = "CART_NOT_FOUND";
    public static final String CART_ACCESS_DENIED = "CART_ACCESS_DENIED";
    public static final String CART_EXPIRED = "CART_EXPIRED";
    public static final String CART_NOT_EDITABLE = "CART_NOT_EDITABLE";
    public static final String CART_EMPTY = "CART_EMPTY";
    public static final String CART_ALREADY_EXISTS = "CART_ALREADY_EXISTS";
    public static final String CART_LIMIT_EXCEEDED = "CART_LIMIT_EXCEEDED";
    public static final String CART_LINE_NOT_FOUND = "CART_LINE_NOT_FOUND";
    public static final String CART_VERSION_CONFLICT = "CART_VERSION_CONFLICT";
    public static final String CUSTOMER_MISMATCH = "CUSTOMER_MISMATCH";
    public static final String ITEM_NOT_FOUND = "ITEM_NOT_FOUND";
    public static final String PRICE_NOT_FOUND = "PRICE_NOT_FOUND";
    public static final String PRICE_CHANGED = "PRICE_CHANGED";
    public static final String INSUFFICIENT_AVAILABILITY = "INSUFFICIENT_AVAILABILITY";
    public static final String CURRENCY_NOT_RESOLVED = "CURRENCY_NOT_RESOLVED";
    public static final String CREDIT_LIMIT_EXCEEDED = "CREDIT_LIMIT_EXCEEDED";
    public static final String ORDER_ALREADY_CREATED = "ORDER_ALREADY_CREATED";
    public static final String ORDER_SUBMISSION_FAILED = "ORDER_SUBMISSION_FAILED";

    private CartErrorCodes() {
    }
}
