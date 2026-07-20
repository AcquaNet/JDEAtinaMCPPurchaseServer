package com.atina.jdeMCPServer.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

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

    private final String atinaIssuer;
    private final String atinaTokenClaim;

    public AuthenticatedJdeIdentity(
            @Value("${jde.atina.jwt.issuer:Issue}") String atinaIssuer,
            @Value("${jde.atina.token-claim:atina_token}") String atinaTokenClaim) {
        this.atinaIssuer = atinaIssuer;
        this.atinaTokenClaim = atinaTokenClaim;
    }

    /**
     * Si el request se autenticó con un token del microservicio de Atina
     * (en vez de Keycloak), devuelve ese token crudo: ES el token de sesión
     * JDE y se usa directamente como X-Approver-Token, sin Identity Bridge.
     */
    public Optional<String> currentAtinaSessionToken() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            if (atinaIssuer.equals(jwt.getClaimAsString("iss"))) {
                return Optional.of(jwt.getTokenValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Si el request se autenticó con un JWT de Keycloak que trae el token de
     * sesión JDE incrustado en el claim "atina_token" (nombre configurable con
     * jde.atina.token-claim), devuelve ese valor. Es la sesión JDE emitida por
     * Atina y se usa directamente como X-Approver-Token, sin Identity Bridge
     * ni vault. Difiere de {@link #currentAtinaSessionToken()} en que ahí el
     * token ES el bearer completo (issuer Atina); acá el bearer es de Keycloak
     * y el token JDE viaja como un claim más.
     */
    public Optional<String> currentAtinaTokenClaim() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String claim = jwtAuth.getToken().getClaimAsString(atinaTokenClaim);
            if (claim != null && !claim.isBlank()) {
                return Optional.of(claim);
            }
        }
        return Optional.empty();
    }

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
