package com.atina.jdeMCPServer.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${jde.mcp.security.expected-audience}")
    private String expectedAudience;

    private final McpResourceMetadataEntryPoint resourceMetadataEntryPoint;

    public SecurityConfig(McpResourceMetadataEntryPoint resourceMetadataEntryPoint) {
        this.resourceMetadataEntryPoint = resourceMetadataEntryPoint;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // el MCP Server no sirve forms; evaluar segun exposicion real
            .authorizeHttpRequests(auth -> auth
                // Metadata RFC 9728: debe ser publico para que el cliente pueda
                // descubrir el Authorization Server ANTES de tener token
                .requestMatchers("/.well-known/**").permitAll()
                // Endpoint MCP real: spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp
                .requestMatchers("/mcp", "/mcp/**").authenticated()
                .anyRequest().permitAll()
            )
            // Entry point custom en ambos hooks: el del resource server cubre los 401
            // por token invalido/ausente en requests Bearer; exceptionHandling queda
            // como fallback global. Ambos agregan resource_metadata al WWW-Authenticate.
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(Customizer.withDefaults())
                .authenticationEntryPoint(resourceMetadataEntryPoint)
            )
            .exceptionHandling(ex -> ex.authenticationEntryPoint(resourceMetadataEntryPoint));

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        // SupplierJwtDecoder difiere el OIDC discovery contra Keycloak hasta el
        // primer token a validar: el server (y los tests de contexto) arrancan
        // aunque Keycloak no este disponible en ese momento.
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);

            OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);

            // Validador de audiencia: sin esto, CUALQUIER token valido de Keycloak
            // (de otro client, de otra realm-app) seria aceptado por este resource server.
            OAuth2TokenValidator<Jwt> audienceValidator =
                jwt -> jwt.getAudience().contains(expectedAudience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "Audiencia no coincide con " + expectedAudience, null));

            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(withIssuer, audienceValidator)));
            return decoder;
        });
    }
}
