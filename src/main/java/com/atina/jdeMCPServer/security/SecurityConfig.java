package com.atina.jdeMCPServer.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // el MCP Server no sirve forms; evaluar segun exposicion real
            .authorizeHttpRequests(auth -> auth
                // Endpoint MCP real: spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp
                .requestMatchers("/mcp", "/mcp/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(OAuth2ResourceServerConfigurer::jwt);

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
