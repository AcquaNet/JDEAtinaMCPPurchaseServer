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

    /**
     * Búsqueda por dueño autenticado, para cuando el Mcp-Session-Id cambió
     * entre llamadas del mismo caller (reconexión del cliente MCP) y
     * findBySessionId ya no encuentra el carrito bajo la sesión anterior. No
     * hay más de un carrito activo por owner en esta primera etapa, así que
     * alcanza con la primera coincidencia no vencida.
     */
    Optional<SalesCart> findByOwnerId(String ownerId);

    /** Creación inicial (falla con CartOperationException si se supera el límite de carritos activos). */
    SalesCart save(SalesCart cart);

    /**
     * Re-indexa un carrito encontrado por findByOwnerId bajo el sessionId
     * actual (quita la entrada vieja, guarda con withSessionId(newSessionId)),
     * para que llamadas subsiguientes en la misma sesión ya lo encuentren por
     * el camino rápido (findBySessionId) sin depender de nuevo del fallback.
     */
    SalesCart rehome(SalesCart cart, String newSessionId);

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
