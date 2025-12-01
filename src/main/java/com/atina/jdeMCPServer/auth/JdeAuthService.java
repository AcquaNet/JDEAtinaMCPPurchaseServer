package com.atina.jdeMCPServer.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class JdeAuthService {

    private final JdeTokenStore tokenStore;

    public JdeAuthService(JdeTokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    /**
     * Devuelve siempre el token que se debe usar contra el microservicio JDE.
     * - Si ya tenemos uno almacenado => devuelve ese.
     * - Si no tenemos ninguno => intenta tomarlo del header Authorization de la request actual.
     */
    public String getOrCreateToken() {
        return tokenStore.getToken().orElseGet(this::initTokenFromAuthorizationHeader);
    }

    /**
     * Lee el JWT inicial del header Authorization: Bearer <token> de la PRIMER request.
     * Si no existe o es inválido, lanza excepción (puedes adaptar esto a tu gusto).
     */
    private String initTokenFromAuthorizationHeader() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attrs == null) {
            throw new IllegalStateException("No current HTTP request. Cannot initialize JDE token.");
        }

        HttpServletRequest request = attrs.getRequest();
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalStateException(
                    "No JDE JWT found in Authorization header. " +
                            "Expected 'Authorization: Bearer <token>' on the first request."
            );
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw new IllegalStateException("Authorization header Bearer token is empty.");
        }

        tokenStore.setToken(token);
        return token;
    }

    /**
     * Actualiza el token cuando el microservicio devuelve uno nuevo (por ejemplo en un header JDEToken).
     */
    public void updateTokenFromResponse(org.springframework.http.HttpHeaders headers) {
        String newToken = headers.getFirst("X-Approver-Token"); // o el nombre real del header
        if (newToken != null && !newToken.isBlank()) {
            tokenStore.setToken(newToken);
        }
    }
}