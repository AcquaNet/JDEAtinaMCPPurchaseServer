package com.atina.jdeMCPServer.auth;

import com.atina.jdeMCPServer.identity.IdentityResolver;
import com.atina.jdeMCPServer.identity.JdeIdentity;
import com.atina.jdeMCPServer.identity.UnmappedIdentityException;
import com.atina.jdeMCPServer.security.AuthenticatedJdeIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class JdeAuthService {

    private static final Logger log = LoggerFactory.getLogger(JdeAuthService.class);

    /** Atributo de request: usuario JDE resuelto por el Identity Bridge en esta request. */
    private static final String BRIDGE_USER_ATTR = "jde.bridge.user";

    private final JdeTokenStore tokenStore;
    private final AuthenticatedJdeIdentity authenticatedIdentity;
    private final IdentityResolver identityResolver;
    private final JdeSessionCache sessionCache;

    public JdeAuthService(JdeTokenStore tokenStore,
                          AuthenticatedJdeIdentity authenticatedIdentity,
                          IdentityResolver identityResolver,
                          JdeSessionCache sessionCache) {
        this.tokenStore = tokenStore;
        this.authenticatedIdentity = authenticatedIdentity;
        this.identityResolver = identityResolver;
        this.sessionCache = sessionCache;
    }

    // ---------------------------------------------------------------
    // Método principal — resuelve token para la sesión actual
    // ---------------------------------------------------------------

    /**
     * Devuelve el token JDE para la request actual.
     * Orden de resolución:
     *   1. Token de un jde_login manual para este Mcp-Session-Id (el login
     *      explícito gana sobre el bridge).
     *   2. Identity Bridge: sub de Keycloak -> identity_mapping -> credencial
     *      del vault -> login automático contra JDE (con cache por jde_user).
     * Si el sub no tiene mapeo y tampoco hubo login manual, lanza excepción
     * con mensaje claro para Claude.
     *
     * Nota: el header Authorization NO se usa como fuente del token JDE:
     * transporta el JWT de Keycloak, validado por Spring Security.
     */
    public String getOrCreateToken() {
        String sessionId = resolveSessionId();

        // 1. Login manual previo (tool jde_login) para esta sesión MCP
        var manualToken = tokenStore.getToken(sessionId);
        if (manualToken.isPresent()) {
            return manualToken.get();
        }

        // 2. Identity Bridge: identidad Keycloak -> sesión JDE automática
        try {
            String sub = authenticatedIdentity.currentSubject();
            JdeIdentity identity = identityResolver.resolve(sub);
            String token = sessionCache.getOrLogin(identity);
            // Recordar el usuario del bridge para que updateTokenFromResponse
            // sepa a qué cache refrescar si Mulesoft renueva el token
            currentRequestAttributes().setAttribute(
                    BRIDGE_USER_ATTR, identity.jdeUser().toUpperCase(), RequestAttributes.SCOPE_REQUEST);
            return token;
        } catch (UnmappedIdentityException e) {
            log.info("Identity Bridge sin mapeo: {}", e.getMessage());
            throw new JdeSessionNotFoundException(
                    "Tu usuario no tiene un usuario JDE asociado todavía (falta el alta en identity_mapping). " +
                            "Pedí el alta del mapeo, o autentícate manualmente con el tool 'jde_login' " +
                            "usando tu usuario y contraseña JDE.");
        } catch (IllegalStateException e) {
            // Sin JWT Keycloak en el contexto (p. ej. flujo fuera de una request MCP)
            throw new JdeSessionNotFoundException(
                    "Sesión JDE no encontrada para esta sesión. " +
                            "Por favor autentícate usando el tool 'jde_login' con tu usuario y contraseña JDE.");
        }
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
     * Si el token de esta request salió del Identity Bridge, se refresca el
     * cache por jde_user; si salió de un jde_login manual, el store por sesión.
     */
    public void updateTokenFromResponse(org.springframework.http.HttpHeaders headers) {
        String newToken = headers.getFirst("X-Approver-Token");
        if (newToken == null || newToken.isBlank()) {
            return;
        }
        Object bridgeUser = currentRequestAttributes().getAttribute(
                BRIDGE_USER_ATTR, RequestAttributes.SCOPE_REQUEST);
        if (bridgeUser != null) {
            sessionCache.refresh((String) bridgeUser, newToken);
        } else {
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

    private RequestAttributes currentRequestAttributes() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new IllegalStateException("No current HTTP request. Cannot resolve JDE token.");
        }
        return attrs;
    }
}