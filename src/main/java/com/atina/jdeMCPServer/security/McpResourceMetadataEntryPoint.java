package com.atina.jdeMCPServer.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * En cada 401 agrega al header WWW-Authenticate la URL del metadata de
 * recurso protegido (RFC 9728):
 *
 *   WWW-Authenticate: Bearer resource_metadata="https://host/.well-known/oauth-protected-resource"
 *
 * Spring Security por defecto responde solo "WWW-Authenticate: Bearer";
 * sin el parametro resource_metadata los clientes MCP no saben donde
 * descubrir el Authorization Server para abrir el browser de login.
 */
@Component
public class McpResourceMetadataEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // URL construida sobre el host del request para que funcione detras de ngrok
        String metadataUrl = UriComponentsBuilder.fromUriString(request.getRequestURL().toString())
                .replacePath("/.well-known/oauth-protected-resource")
                .replaceQuery(null)
                .toUriString();
        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + metadataUrl + "\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
