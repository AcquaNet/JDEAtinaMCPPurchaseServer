package com.atina.jdeMCPServer.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Spring Filter ensuring ThreadLocal cleanup when MCP request ends.
 * 
 * Wraps all request processing in try-finally to guarantee that
 * CorrelationIdContext is cleaned up after request completes, preventing
 * ThreadLocal pollution in case of request re-pooling.
 */
@Component
public class CorrelationIdFilter implements Filter {
    
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private final CorrelationIdContext correlationIdContext;
    
    public CorrelationIdFilter(CorrelationIdContext correlationIdContext) {
        this.correlationIdContext = correlationIdContext;
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            String id = correlationIdContext.getCorrelationId();
            correlationIdContext.clear();
            log.debug("Cleared correlation ID after request: {}", id);
        }
    }
}
