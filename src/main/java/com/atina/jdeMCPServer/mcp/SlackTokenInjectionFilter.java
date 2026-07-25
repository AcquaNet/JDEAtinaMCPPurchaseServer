package com.atina.jdeMCPServer.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Bypass SOLO PARA DEMOS: la integracion de Slack no permite mandar un header
 * Authorization propio en sus llamadas a /mcp. Este filtro corre antes que
 * Spring Security: si el request al endpoint MCP real no trae Authorization,
 * inyecta un Bearer fijo (jde.mcp.slack-demo.token) -- mismo /mcp para todos,
 * todos los tools funcionan igual que con cualquier otro cliente autenticado.
 * Clientes que SI mandan su propio token (Claude Desktop, Postman,
 * mcp-remote) no se ven afectados en absoluto.
 *
 * El trigger primario es "no Authorization"; si ademas se configura
 * jde.mcp.slack-demo.user-agent (ej. "Slack-MCP-Client", el valor real
 * identificado en el log de este filtro), tambien se exige que el
 * User-Agent lo contenga -- para no inyectar el token a cualquier request
 * sin token que llegue, solo al cliente MCP de Slack. Vacio (default) = solo
 * importa que falte Authorization, igual que antes.
 *
 * Apagado por defecto (jde.mcp.slack-demo.enabled=false).
 */
//@Component
//@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlackTokenInjectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SlackTokenInjectionFilter.class);

    @Value("${jde.mcp.slack-demo.enabled:false}")
    private boolean enabled;

    @Value("${jde.mcp.slack-demo.token:}")
    private String demoToken;

    @Value("${jde.mcp.slack-demo.user-agent:}")
    private String expectedUserAgent;

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}")
    private String mcpEndpoint;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean isMcpRequest = mcpEndpoint.equals(request.getRequestURI());
        boolean hasAuthHeader = request.getHeader("Authorization") != null;
        String userAgent = request.getHeader("User-Agent");
        boolean userAgentMatches = expectedUserAgent.isBlank() || expectedUserAgent.equals(userAgent);

        if (enabled && !demoToken.isBlank() && isMcpRequest && !hasAuthHeader && userAgentMatches) {
            log.info("SlackTokenInjectionFilter: request sin Authorization a {} -- inyectando token fijo. "
                            + "User-Agent=[{}], headers=[{}]",
                    mcpEndpoint, userAgent, headerDump(request));
            chain.doFilter(new BearerInjectingRequest(request, demoToken), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String headerDump(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            sb.append(name).append('=').append(request.getHeader(name)).append("; ");
        }
        return sb.toString();
    }

    /** Agrega el Bearer fijo, dejando el resto del request intacto. */
    private static class BearerInjectingRequest extends HttpServletRequestWrapper {
        private final String bearerValue;

        BearerInjectingRequest(HttpServletRequest request, String token) {
            super(request);
            this.bearerValue = "Bearer " + token;
        }

        @Override
        public String getHeader(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return bearerValue;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(bearerValue));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(n -> n.equalsIgnoreCase("Authorization"))) {
                names.add("Authorization");
            }
            return Collections.enumeration(names);
        }
    }
}
