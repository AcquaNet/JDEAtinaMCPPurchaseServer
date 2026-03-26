package com.atina.jdeMCPServer.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JdeTokenStore {

    // ---------------------------------------------------------------
    // Entrada interna: JWT + su expiración parseada del claim "exp"
    // ---------------------------------------------------------------
    private record TokenEntry(String jwt, Instant expiry) {
        boolean isExpired() {
            // Considera expirado 5 minutos antes para dar margen
            return Instant.now().isAfter(expiry.minusSeconds(300));
        }
    }

    // Clave: Mcp-Session-Id (o IP como fallback)
    private final ConcurrentHashMap<String, TokenEntry> sessionTokens = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------
    // API pública
    // ---------------------------------------------------------------

    public Optional<String> getToken(String sessionId) {
        TokenEntry entry = sessionTokens.get(sessionId);
        if (entry == null || entry.isExpired()) {
            sessionTokens.remove(sessionId);   // limpieza proactiva
            return Optional.empty();
        }
        return Optional.of(entry.jwt());
    }

    public void setToken(String sessionId, String jwt) {
        Instant expiry = parseExpiry(jwt);
        sessionTokens.put(sessionId, new TokenEntry(jwt, expiry));
    }

    public void clear(String sessionId) {
        sessionTokens.remove(sessionId);
    }

    public boolean hasValidToken(String sessionId) {
        return getToken(sessionId).isPresent();
    }

    // ---------------------------------------------------------------
    // Compatibilidad: estos métodos sin sessionId siguen compilando
    // pero delegan a una sesión "legacy" para no romper código viejo
    // durante la migración. Eliminar una vez migrado todo.
    // ---------------------------------------------------------------
    /** @deprecated Usar {@link #getToken(String)} con sessionId */
    @Deprecated
    public Optional<String> getToken() {
        return getToken("__legacy__");
    }

    /** @deprecated Usar {@link #setToken(String, String)} con sessionId */
    @Deprecated
    public void setToken(String jwt) {
        setToken("__legacy__", jwt);
    }

    /** @deprecated Usar {@link #clear(String)} con sessionId */
    @Deprecated
    public void clear() {
        clear("__legacy__");
    }

    // ---------------------------------------------------------------
    // Parseo del claim "exp" del JWT (sin verificar firma)
    // ---------------------------------------------------------------
    private Instant parseExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return Instant.now().plusSeconds(3600); // fallback 1h

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            // Extraer "exp": 1234567890 del JSON sin dependencias externas
            int idx = payload.indexOf("\"exp\"");
            if (idx == -1) return Instant.now().plusSeconds(3600);

            int start = payload.indexOf(':', idx) + 1;
            int end   = payload.indexOf(',', start);
            if (end == -1) end = payload.indexOf('}', start);

            long exp = Long.parseLong(payload.substring(start, end).trim());
            return Instant.ofEpochSecond(exp);

        } catch (Exception e) {
            return Instant.now().plusSeconds(3600); // fallback 1h si no se puede parsear
        }
    }
}