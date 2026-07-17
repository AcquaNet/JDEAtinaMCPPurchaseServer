package com.atina.jdeMCPServer.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class JdeAuthService {

    private static final Logger log = LoggerFactory.getLogger(JdeAuthService.class);

    private final JdeTokenStore tokenStore;

    public JdeAuthService(JdeTokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    // ---------------------------------------------------------------
    // Método principal — resuelve token para la sesión actual
    // ---------------------------------------------------------------

    /**
     * Devuelve el token JDE para la sesión actual (almacenado y vigente
     * para este Mcp-Session-Id). Si no existe, lanza excepción con mensaje
     * claro para Claude.
     *
     * Nota: el header Authorization ya NO se usa como fuente del token JDE.
     * Ahora transporta el JWT de Keycloak, validado por Spring Security
     * (ver SecurityConfig); no es un token Mulesoft/JDE.
     */
    public String getOrCreateToken() {
        String sessionId = resolveSessionId();
        return tokenStore.getToken(sessionId)
                .orElseThrow(() -> new JdeSessionNotFoundException(
                        "Sesión JDE no encontrada para esta sesión. " +
                                "Por favor autentícate usando el tool 'jde_login' con tu usuario y contraseña JDE."
                ));
    }

    /**
     * Guarda un token recién obtenido (por ejemplo desde jde_login tool)
     * asociado a la sesión activa.
     */
    public void storeToken(String jwt) {
        String sessionId = resolveSessionId();
        tokenStore.setToken(sessionId, jwt);
        log.info("Token almacenado para sesión [{}]", sessionId);
    }

    /**
     * Invalida la sesión actual (logout explícito).
     */
    public void clearToken() {
        tokenStore.clear(resolveSessionId());
    }

    /**
     * Verifica si la sesión actual tiene un token válido y vigente.
     */
    public boolean hasValidToken() {
        return tokenStore.hasValidToken(resolveSessionId());
    }

    /**
     * Actualiza el token cuando Mulesoft devuelve uno renovado en headers.
     */
    public void updateTokenFromResponse(org.springframework.http.HttpHeaders headers) {
        String newToken = headers.getFirst("X-Approver-Token");
        if (newToken != null && !newToken.isBlank()) {
            storeToken(newToken);
        }
    }

    // ---------------------------------------------------------------
    // Resolución del session ID
    // ---------------------------------------------------------------

    /**
     * Extrae el identificador de sesión MCP del request actual.
     * - Streamable HTTP usa el header "Mcp-Session-Id"
     * - Fallback: IP remota (útil en desarrollo con el inspector)
     */
    public String resolveSessionId() {
        HttpServletRequest request = currentRequest();
        String sessionId = request.getHeader("Mcp-Session-Id");
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        // Fallback para el inspector o clientes que no envían el header
        String fallback = request.getRemoteAddr();
        log.debug("Mcp-Session-Id no presente, usando IP como fallback: {}", fallback);
        return fallback;
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new IllegalStateException("No current HTTP request. Cannot resolve JDE token.");
        }
        return attrs.getRequest();
    }
}