package com.atina.jdeMCPServer.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Punto unico para leer la identidad Keycloak del request actual,
 * en vez de que cada handler MCP repita la logica de extraccion.
 *
 * Uso tipico dentro de un handler de herramienta MCP (ej. jde_login,
 * jde_list_pending_purchase_orders, etc.):
 *
 *   String keycloakSub = authenticatedJdeIdentity.currentSubject();
 *   JdeCredential credential = identityBridge.resolve(keycloakSub);
 *
 * El "identityBridge.resolve(...)" es el componente que todavia falta
 * construir (mapea sub de Keycloak -> usuario/environment/role de JDE).
 */
@Component
public class AuthenticatedJdeIdentity {

    public String currentSubject() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            return jwt.getSubject();
        }
        throw new IllegalStateException("No hay un JWT autenticado en el contexto de seguridad actual");
    }

    /**
     * Para el sub-caso de vendedores externos (Caso 2b), donde el mapeo a
     * cuenta tecnica JDE viene de un claim custom del grupo, no del sub individual.
     */
    public String currentJdeTechnicalAccountClaim() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            return jwt.getClaimAsString("jde_technical_account");
        }
        throw new IllegalStateException("No hay un JWT autenticado en el contexto de seguridad actual");
    }
}
