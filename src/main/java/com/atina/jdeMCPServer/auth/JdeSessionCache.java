package com.atina.jdeMCPServer.auth;

import com.atina.jdeMCPServer.identity.JdeIdentity;
import com.atina.jdeMCPServer.vault.CredentialVault;
import com.atina.jdeMCPServer.vault.JdeCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache del JWT de Atina/Mulesoft para el Identity Bridge.
 *
 * - Keyed por jde_user (no por keycloak_sub): si dos usuarios de Keycloak
 *   mapean al mismo jde_user, comparten sesión JDE.
 * - Renovación proactiva: si el JWT cacheado expira en menos de 60 segundos,
 *   se reloguea antes de usarlo, en vez de esperar a que falle una request real.
 * - Cache en memoria (ConcurrentHashMap, mismo patrón que JdeTokenStore).
 *   Si el MCP Server pasa a correr en más de una instancia, migrar a un
 *   cache compartido (Redis).
 */
@Component
public class JdeSessionCache {

    private static final Logger log = LoggerFactory.getLogger(JdeSessionCache.class);

    /** Margen de renovación proactiva antes de la expiración real del JWT. */
    private static final long RENEWAL_BUFFER_SECONDS = 60;

    private record SessionEntry(String jwt, Instant expiry) {
        boolean needsRenewal() {
            return Instant.now().isAfter(expiry.minusSeconds(RENEWAL_BUFFER_SECONDS));
        }
    }

    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    // Lock por jde_user para evitar logins concurrentes duplicados del mismo usuario
    private final ConcurrentHashMap<String, Object> loginLocks = new ConcurrentHashMap<>();

    private final CredentialVault credentialVault;
    private final JdeAuthClient authClient;

    public JdeSessionCache(CredentialVault credentialVault, JdeAuthClient authClient) {
        this.credentialVault = credentialVault;
        this.authClient = authClient;
    }

    /**
     * Devuelve un JWT JDE vigente para la identidad dada, logueando contra
     * JDE (con la credencial del vault) solo cuando hace falta.
     */
    public String getOrLogin(JdeIdentity identity) {
        String jdeUser = identity.jdeUser().toUpperCase();

        SessionEntry entry = sessions.get(jdeUser);
        if (entry != null && !entry.needsRenewal()) {
            return entry.jwt();
        }

        Object lock = loginLocks.computeIfAbsent(jdeUser, k -> new Object());
        synchronized (lock) {
            // Re-chequear: otro thread pudo haber renovado mientras esperábamos el lock
            entry = sessions.get(jdeUser);
            if (entry != null && !entry.needsRenewal()) {
                return entry.jwt();
            }
            return login(jdeUser, identity);
        }
    }

    /**
     * Actualiza la sesión cacheada cuando Mulesoft devuelve un token renovado
     * en la respuesta de una request normal.
     */
    public void refresh(String jdeUser, String jwt) {
        sessions.put(jdeUser.toUpperCase(), new SessionEntry(jwt, parseExpiry(jwt)));
    }

    public void evict(String jdeUser) {
        sessions.remove(jdeUser.toUpperCase());
    }

    private String login(String jdeUser, JdeIdentity identity) {
        boolean renewal = sessions.containsKey(jdeUser);
        log.info("{} sesión JDE para usuario [{}] (env={}, role={})",
                renewal ? "Renovando" : "Iniciando",
                jdeUser, identity.jdeEnvironment(), identity.jdeRole());

        JdeCredential credential = credentialVault.getCredential(jdeUser);

        JdeAuthClient.LoginResult result = authClient.login(
                credential.user(),
                credential.password(),
                identity.jdeEnvironment(),
                identity.jdeRole()
        );

        sessions.put(jdeUser, new SessionEntry(result.token(), parseExpiry(result.token())));
        log.info("Sesión JDE activa para usuario [{}]", jdeUser);
        return result.token();
    }

    // Expiración parseada del claim "exp" del JWT (mismo criterio que JdeTokenStore)
    private Instant parseExpiry(String jwt) {
        try {
            String payload = jwt.split("\\.")[1];
            String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            var node = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(json);
            long exp = node.path("exp").asLong(0);
            if (exp > 0) {
                return Instant.ofEpochSecond(exp);
            }
        } catch (Exception e) {
            log.warn("No se pudo parsear la expiración del JWT JDE: {}", e.getMessage());
        }
        // Sin claim exp legible: asumir vida corta para forzar renovación pronto
        return Instant.now().plusSeconds(300);
    }
}
