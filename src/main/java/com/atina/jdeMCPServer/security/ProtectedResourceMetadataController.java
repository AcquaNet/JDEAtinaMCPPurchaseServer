package com.atina.jdeMCPServer.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * Metadata de recurso protegido OAuth2 (RFC 9728).
 *
 * Los clientes MCP (mcp-remote, Claude.ai) consultan este endpoint despues
 * de recibir un 401 (cuya URL viene en el WWW-Authenticate, ver
 * McpResourceMetadataEntryPoint) para descubrir contra que Authorization
 * Server deben iniciar el flujo de login.
 *
 * Se mapea tambien la variante con path (/.well-known/oauth-protected-resource/mcp)
 * porque el spec MCP deriva la URL del metadata a partir de la URI del recurso,
 * que incluye el path /mcp.
 */
@RestController
public class ProtectedResourceMetadataController {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    public Map<String, Object> metadata() {
        // Base tomada del request actual para que funcione igual detras de ngrok
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return Map.of(
                "resource", base + "/mcp",
                "authorization_servers", List.of(issuerUri),
                "bearer_methods_supported", List.of("header")
        );
    }
}
