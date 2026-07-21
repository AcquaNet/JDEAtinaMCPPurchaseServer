package com.atina.jdeMCPServer.auth;

import com.atina.jdeMCPServer.identity.IdentityResolver;
import com.atina.jdeMCPServer.identity.JdeIdentity;
import com.atina.jdeMCPServer.identity.UnmappedIdentityException;
import com.atina.jdeMCPServer.security.AuthenticatedJdeIdentity;
import com.atina.jdeMCPServer.vault.AtinaSessionVault;
import com.atina.jdeMCPServer.vault.VaultUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@Service
public class JdeAuthService {

    private static final Logger log = LoggerFactory.getLogger(JdeAuthService.class);

    /** Atributo de request: usuario JDE resuelto por el Identity Bridge en esta request. */
    private static final String BRIDGE_USER_ATTR = "jde.bridge.user";

    /**
     * Atributo de request: marca que el token de esta request salió de la
     * estrategia de Atina (claim o vault). updateTokenFromResponse lo usa para
     * NO intentar refrescar (no hay dónde reinyectar un token renovado), sin
     * tener que re-consultar el claim ni —peor— re-pegarle al vault.
     */
    private static final String ATINA_STRATEGY_ATTR = "jde.atina.strategyToken";

    private final JdeTokenStore tokenStore;
    private final AuthenticatedJdeIdentity authenticatedIdentity;
    private final IdentityResolver identityResolver;
    private final JdeSessionCache sessionCache;
    private final AtinaSessionVault atinaSessionVault;
    private final AtinaSessionSource atinaSessionSource;

    public JdeAuthService(JdeTokenStore tokenStore,
                          AuthenticatedJdeIdentity authenticatedIdentity,
                          IdentityResolver identityResolver,
                          JdeSessionCache sessionCache,
                          AtinaSessionVault atinaSessionVault,
                          @Value("${jde.atina.session-source:claim}") String atinaSessionSource) {
        this.tokenStore = tokenStore;
        this.authenticatedIdentity = authenticatedIdentity;
        this.identityResolver = identityResolver;
        this.sessionCache = sessionCache;
        this.atinaSessionVault = atinaSessionVault;
        this.atinaSessionSource = AtinaSessionSource.parse(atinaSessionSource);
        log.info("Fuente del token de sesión Atina (jde.atina.session-source): {}", this.atinaSessionSource);
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

        // 0. Bearer emitido por el microservicio de Atina: ya ES el token de
        //    sesión JDE (firmado y validado por Spring Security), se usa directo
        var atinaToken = authenticatedIdentity.currentAtinaSessionToken();
        if (atinaToken.isPresent()) {
            return atinaToken.get();
        }

        // 0.b Token de sesión JDE de Atina para un usuario autenticado con Keycloak.
        //     Puede venir del claim "atina_token" del JWT (etapa 1) o de OpenBao
        //     (etapa 2); jde.atina.session-source define cuál se usa y en qué orden.
        //     Se usa directo como X-Approver-Token, sin Identity Bridge ni login.
        var atinaSession = resolveAtinaSessionToken();
        if (atinaSession.isPresent()) {
            currentRequestAttributes().setAttribute(
                    ATINA_STRATEGY_ATTR, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
            return atinaSession.get();
        }

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
     * Resuelve el token de sesión JDE de Atina según jde.atina.session-source,
     * haciendo convivir el claim del JWT (etapa 1) y el guardado en OpenBao
     * (etapa 2). En los modos con fallback, si el vault está caído se degrada
     * al claim en vez de romper la operación.
     */
    private Optional<String> resolveAtinaSessionToken() {
        return switch (atinaSessionSource) {
            case CLAIM -> atinaTokenFromClaim();
            case VAULT -> atinaTokenFromVault();
            case CLAIM_THEN_VAULT -> {
                Optional<String> fromClaim = atinaTokenFromClaim();
                yield fromClaim.isPresent() ? fromClaim : atinaTokenFromVault();
            }
            case VAULT_THEN_CLAIM -> {
                Optional<String> fromVault = atinaTokenFromVaultSafe();
                yield fromVault.isPresent() ? fromVault : atinaTokenFromClaim();
            }
        };
    }

    /** Etapa 1: token en el claim "atina_token" del JWT de Keycloak. */
    private Optional<String> atinaTokenFromClaim() {
        return authenticatedIdentity.currentAtinaTokenClaim();
    }

    /** Etapa 2: token guardado en OpenBao, keyed por el sub de Keycloak. */
    private Optional<String> atinaTokenFromVault() {
        return atinaSessionVault.getAtinaSessionToken(authenticatedIdentity.currentSubject());
    }

    /**
     * Como {@link #atinaTokenFromVault()} pero degrada a vacío si el vault no
     * está disponible, para permitir el fallback al claim en modo VAULT_THEN_CLAIM.
     */
    private Optional<String> atinaTokenFromVaultSafe() {
        try {
            return atinaTokenFromVault();
        } catch (VaultUnavailableException e) {
            log.warn("OpenBao no disponible al resolver atina_token; se intenta el claim. {}",
                    e.getMessage());
            return Optional.empty();
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
        // Token de Atina (bearer directo, o resuelto por la estrategia claim/vault):
        // la sesión JDE la controla Atina/Keycloak/vault, no la almacenamos ni
        // refrescamos de nuestro lado (no hay dónde reinyectar un token renovado).
        if (authenticatedIdentity.currentAtinaSessionToken().isPresent()) {
            return;
        }
        Object atinaStrategy = currentRequestAttributes().getAttribute(
                ATINA_STRATEGY_ATTR, RequestAttributes.SCOPE_REQUEST);
        if (Boolean.TRUE.equals(atinaStrategy)) {
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