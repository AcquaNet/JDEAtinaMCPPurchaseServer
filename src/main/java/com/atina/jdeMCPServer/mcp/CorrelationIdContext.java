package com.atina.jdeMCPServer.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ThreadLocal holder for correlation ID per MCP request.
 * 
 * Manages the lifecycle of correlation IDs extracted from MCP tool requests.
 * - Extracts from client-supplied _meta.correlationId (optional)
 * - Auto-generates UUID-based ID if not provided
 * - Accessible to all services/clients executing within the same request thread
 * - Should be cleared after request completes (via CorrelationIdFilter)
 */
@Component
public class CorrelationIdContext {
    
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdContext.class);
    private static final ThreadLocal<String> correlationId = new ThreadLocal<>();
    
    /**
     * Set correlation ID from MCP request _meta.
     * If id is null or blank, generates UUID-based ID: "auto-<UUID>".
     * 
     * @param id correlation ID from client (may be null)
     */
    public void setCorrelationId(String id) {
        String finalId = (id != null && !id.isBlank()) ? id : generateAutoId();
        correlationId.set(finalId);
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
            correlationId.set(autoId);
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
