package com.atina.jdeMCPServer.cart.services;

import com.atina.jdeMCPServer.cart.model.CreatedOrderRef;
import com.atina.jdeMCPServer.cart.model.SalesCart;
import com.atina.jdeMCPServer.salesorder.model.CreateSalesOrderRequest;

/**
 * Resultado de SalesCartService.prepareSubmission: o bien el pedido ya
 * existía (recovered=true, no hay que llamar al Gateway), o bien el carrito
 * quedó marcado SUBMITTING y el payload está listo para enviar (request no
 * null). Es determinístico y reconstruible desde el estado del carrito --
 * llamarlo de nuevo con el mismo expectedCartVersion mientras el carrito
 * sigue SUBMITTING reconstruye el mismo request/externalReference, lo que
 * permite que JdeSalesCartTools reconecte con la misma tarea del
 * LongRunningTaskRegistry por key sin volver a marcar el estado.
 */
public record SubmissionPreparation(
        SalesCart cart,
        boolean recovered,
        CreatedOrderRef recoveredOrder,
        CreateSalesOrderRequest request,
        String externalReference,
        String warning
) {
    public static SubmissionPreparation recovered(SalesCart cart, CreatedOrderRef order) {
        return new SubmissionPreparation(cart, true, order, null, order.externalReference(), "");
    }

    public static SubmissionPreparation ready(SalesCart cart, CreateSalesOrderRequest request,
                                               String externalReference, String warning) {
        return new SubmissionPreparation(cart, false, null, request, externalReference, warning);
    }
}
