package com.atina.jdeMCPServer.cart.services;

import com.atina.jdeMCPServer.auth.JdeAuthService;
import com.atina.jdeMCPServer.security.AuthenticatedJdeIdentity;
import org.springframework.stereotype.Component;

/**
 * Centraliza la resolución de sessionId/ownerId para las tools de carrito, a
 * partir de componentes que YA existen y no se modifican:
 * JdeAuthService.resolveSessionId() (header Mcp-Session-Id, fallback IP) y
 * AuthenticatedJdeIdentity.currentSubject() (sub del JWT de Keycloak).
 *
 * Caso Atina directo (bearer del microservicio Atina, sin JWT Keycloak real
 * con "sub"): currentSubject() lanza IllegalStateException. No existe hoy un
 * identificador de usuario individual verificable en ese escenario más fino
 * que el propio Mcp-Session-Id, así que se degrada ownerId al sessionId --
 * mismo nivel de aislamiento que ya usa JdeTokenStore (keyed solo por
 * sessionId, sin un ownerId adicional). Supuesto explícito, no un hecho
 * verificado contra un caso real de uso con Atina.
 */
@Component
public class CartOwnerResolver {

    private final JdeAuthService authService;
    private final AuthenticatedJdeIdentity authenticatedIdentity;

    public CartOwnerResolver(JdeAuthService authService, AuthenticatedJdeIdentity authenticatedIdentity) {
        this.authService = authService;
        this.authenticatedIdentity = authenticatedIdentity;
    }

    public CartOwner resolveCurrent() {
        String sessionId = authService.resolveSessionId();
        String ownerId = resolveOwnerId(sessionId);
        return new CartOwner(sessionId, ownerId);
    }

    private String resolveOwnerId(String sessionId) {
        try {
            return authenticatedIdentity.currentSubject();
        } catch (IllegalStateException e) {
            return sessionId;
        }
    }
}
