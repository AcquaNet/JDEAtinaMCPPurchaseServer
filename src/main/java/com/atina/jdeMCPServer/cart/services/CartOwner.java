package com.atina.jdeMCPServer.cart.services;

/**
 * Identidad resuelta del caller actual para operaciones de carrito: sessionId
 * (clave del repositorio) + ownerId (defensa en profundidad, ver
 * CartOwnerResolver). Ningún tool recibe estos valores como parámetro --
 * siempre se resuelven server-side.
 */
public record CartOwner(String sessionId, String ownerId) {
}
