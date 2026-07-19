package com.atina.jdeMCPServer.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * Autorización por roles de realm de Keycloak para los tools MCP.
 *
 * Por qué acá y no en SecurityConfig: todos los tools entran por el mismo
 * endpoint HTTP (/mcp, JSON-RPC), así que las reglas por URL de Spring
 * Security no pueden distinguir "listar" de "aprobar". La autorización
 * fina se chequea a nivel del tool, con este componente.
 *
 * Los roles de realm viajan en el claim realm_access.roles del JWT de
 * Keycloak (se asignan en: consola -> Users -> <user> -> Role mapping).
 *
 * Política para tokens de Atina: se consideran autorizados. Ese token ya ES
 * una sesión JDE emitida con un role JDE (claim "role"); la autorización
 * la aplica JDE en el backend, no este server.
 */
@Component
public class RealmRoleGuard {

    private final String atinaIssuer;

    public RealmRoleGuard(@Value("${jde.atina.jwt.issuer:Issue}") String atinaIssuer) {
        this.atinaIssuer = atinaIssuer;
    }

    /**
     * ¿El token del request actual tiene el rol de realm pedido?
     */
    public boolean hasRealmRole(String role) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return false;
        }
        Jwt jwt = jwtAuth.getToken();

        // Token de Atina: autoriza JDE con su propio role, no Keycloak
        if (atinaIssuer.equals(jwt.getClaimAsString("iss"))) {
            return true;
        }

        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return false;
        }
        return realmAccess.get("roles") instanceof Collection<?> roles && roles.contains(role);
    }
}
