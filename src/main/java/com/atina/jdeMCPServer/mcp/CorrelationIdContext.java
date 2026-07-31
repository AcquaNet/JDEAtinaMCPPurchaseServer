package com.atina.jdeMCPServer.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * ThreadLocal holder for correlation ID per MCP request.
 *
 * Manages the lifecycle of correlation IDs extracted from MCP tool requests.
 * - Extracts from client-supplied _meta.correlationId (optional)
 * - Auto-generates UUID-based ID if not provided
 * - Accessible to all services/clients executing within the same request thread
 * - Also mirrored into SLF4J's MDC (key "correlationId") so it shows up on
 *   every log line via logging.pattern.level (see application.properties) --
 *   MDC is itself thread-bound, so it follows the exact same lifecycle
 *   (set/clear) as the ThreadLocal below, including across
 *   wrapForBackgroundThread's pooled threads.
 * - Should be cleared after request completes (via CorrelationIdFilter)
 */
@Component
public class CorrelationIdContext {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdContext.class);
    private static final String MDC_KEY = "correlationId";
    private static final ThreadLocal<String> correlationId = new ThreadLocal<>();

    /**
     * Set correlation ID from MCP request _meta.
     * If id is null or blank, generates UUID-based ID: "auto-<UUID>".
     *
     * @param id correlation ID from client (may be null)
     */
    public void setCorrelationId(String id) {
        String finalId = (id != null && !id.isBlank()) ? id : generateAutoId();
        apply(finalId);
        log.debug("Correlation ID set: {}", finalId);
    }

    /**
     * Get current correlation ID.
     * Never returns null — auto-generates if not previously set.
     * This ensures every request has a correlation ID for Gateway tracing.
     *
     * @return current correlation ID (never null)
     */
    public String getCorrelationId() {
        String id = correlationId.get();
        if (id == null) {
            String autoId = generateAutoId();
            apply(autoId);
            log.debug("Auto-generated correlation ID: {}", autoId);
            return autoId;
        }
        return id;
    }

    /**
     * Clear correlation ID when request ends.
     * Should be called from CorrelationIdFilter.doFilter() finally block.
     */
    public void clear() {
        correlationId.remove();
        MDC.remove(MDC_KEY);
    }

    private void apply(String id) {
        correlationId.set(id);
        MDC.put(MDC_KEY, id);
    }

    /**
     * Extracts _meta.correlationId from an MCP tool call (any JSON type -- not
     * necessarily a String, since _meta is client-controlled), sets it as the
     * current correlation ID (or auto-generates one if absent/blank) and
     * returns the resolved value. Single entry point used by every @McpTool
     * method instead of reading meta.get(...) directly, so a client sending a
     * non-string _meta.correlationId can't throw a ClassCastException out of
     * tool code.
     */
    public String extractAndSet(McpMeta meta) {
        Object raw = meta != null ? meta.get("correlationId") : null;
        setCorrelationId(raw != null ? raw.toString() : null);
        return getCorrelationId();
    }

    /**
     * Wraps background work submitted to a separate executor (e.g.
     * LongRunningTaskRegistry's pool) so it runs under the correlation ID
     * resolved in the original request thread. The registry's Supplier runs
     * on a pooled thread with no access to this ThreadLocal (or to
     * RequestContextHolder -- see LongRunningTaskRegistry's javadoc, same
     * constraint already solved there for the JDE token): the caller must
     * resolve the correlation ID beforehand, in the request thread, and pass
     * it in here as plain data. Clears afterwards so a reused pool thread
     * never leaks one task's correlation ID into the next.
     */
    public <T> Supplier<T> wrapForBackgroundThread(String correlationId, Supplier<T> work) {
        return () -> {
            setCorrelationId(correlationId);
            try {
                return work.get();
            } finally {
                clear();
            }
        };
    }

    /**
     * Generate auto ID with "auto-" prefix to distinguish from client-supplied.
     * Format: "auto-<UUID>"
     *
     * @return auto-generated correlation ID
     */
    private String generateAutoId() {
        return "auto-" + UUID.randomUUID();
    }
}
