package com.atina.jdeMCPServer.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * En cada 401 arma el header WWW-Authenticate con:
 *  - el motivo del rechazo (error / error_description), igual que el entry
 *    point estandar de Spring — clave para diagnosticar (token vencido vs
 *    firma invalida vs audiencia incorrecta se ven distinto);
 *  - la URL del metadata de recurso protegido (RFC 9728), que los clientes
 *    MCP usan para descubrir el Authorization Server y abrir el login.
 *
 * Ejemplo:
 *   WWW-Authenticate: Bearer error="invalid_token", error_description="Jwt expired at ...",
 *                     resource_metadata="https://host/.well-known/oauth-protected-resource"
 */
@Component
public class McpResourceMetadataEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        List<String> params = new ArrayList<>();

        if (authException instanceof OAuth2AuthenticationException oauthEx) {
            OAuth2Error error = oauthEx.getError();
            if (error.getErrorCode() != null && !error.getErrorCode().isBlank()) {
                params.add("error=\"" + sanitize(error.getErrorCode()) + "\"");
            }
            if (error.getDescription() != null && !error.getDescription().isBlank()) {
                params.add("error_description=\"" + sanitize(error.getDescription()) + "\"");
            }
        }

        // URL construida sobre el host del request para que funcione detras de ngrok
        String metadataUrl = UriComponentsBuilder.fromUriString(request.getRequestURL().toString())
                .replacePath("/.well-known/oauth-protected-resource")
                .replaceQuery(null)
                .toUriString();
        params.add("resource_metadata=\"" + metadataUrl + "\"");

        response.setHeader("WWW-Authenticate", "Bearer " + String.join(", ", params));
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    /** Los valores van entre comillas dentro del header: sin comillas ni saltos de linea. */
    private String sanitize(String value) {
        return value.replace("\"", "'").replace("\r", " ").replace("\n", " ");
    }
}
