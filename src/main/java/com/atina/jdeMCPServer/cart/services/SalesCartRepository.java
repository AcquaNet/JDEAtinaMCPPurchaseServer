package com.atina.jdeMCPServer.cart.services;

import com.atina.jdeMCPServer.cart.model.SalesCart;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Repositorio del carrito activo por sesión MCP. La interfaz está pensada
 * para que una implementación futura con Redis (locking distribuido /
 * optimistic locking WATCH-MULTI-EXEC) pueda reemplazar
 * InMemorySalesCartRepository sin tocar SalesCartService ni las tools.
 */
public interface SalesCartRepository {

    Optional<SalesCart> findBySessionId(String sessionId);

    /** Creación inicial (falla con CartOperationException si se supera el límite de carritos activos). */
    SalesCart save(SalesCart cart);

    /**
     * Aplica {@code mutator} de forma atómica sobre el carrito de {@code sessionId}
     * (o sobre {@code null} si no existe -- el mutator decide qué hacer, típicamente
     * lanzar CartOperationException con CART_NOT_FOUND). Si el mutator lanza una
     * excepción, la entrada existente no se modifica.
     */
    SalesCart update(String sessionId, UnaryOperator<SalesCart> mutator);

    void deleteBySessionId(String sessionId);

    void removeExpired();

    int activeCount();
}
