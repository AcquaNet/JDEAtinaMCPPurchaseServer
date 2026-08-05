package com.atina.jdeMCPServer.cart.model;

/**
 * Máquina de estados del carrito de compras. Solo OPEN/READY_FOR_CONFIRMATION
 * admiten mutación (agregar/actualizar/eliminar línea); cualquier mutación en
 * READY_FOR_CONFIRMATION vuelve a OPEN (fuerza a revalidar antes de confirmar).
 * SUBMITTING es transitorio mientras se llama a JDE. ORDER_CREATED es terminal
 * para efectos de reenvío: jde_submit_current_sales_cart nunca reenvía un
 * carrito en ese estado.
 */
public enum CartStatus {
    OPEN,
    VALIDATING,
    READY_FOR_CONFIRMATION,
    SUBMITTING,
    ORDER_CREATED,
    CANCELLED,
    EXPIRED
}
