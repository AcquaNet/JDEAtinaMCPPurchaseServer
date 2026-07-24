package com.atina.jdeMCPServer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${jde.mcp.security.expected-audience}")
    private String expectedAudience;

    // Vacío por defecto = comportamiento de siempre: discovery completo contra
    // issuerUri (JwtDecoders.fromIssuerLocation), que exige que este proceso pueda
    // alcanzar issuerUri directamente y que el "issuer" del .well-known devuelto
    // coincida exactamente con issuerUri. Funciona cuando issuerUri es alcanzable
    // desde donde corre el MCP Server (IDE local, o detrás de ngrok/Caddy donde el
    // mismo dominio público también resuelve puertas adentro).
    // Setear SOLO cuando el MCP Server corre containerizado y el issuer público (lo
    // que Keycloak realmente firma en "iss", ej. http://localhost:8180 para el
    // profile "local" sin ngrok) NO es alcanzable desde el contenedor -- ahí apuntar
    // esto a la URL interna real del JWKS (ej. Docker Compose:
    // http://keycloak:8080/realms/jde-integration/protocol/openid-connect/certs).
    // issuerUri se sigue usando para validar el claim "iss" del token (debe coincidir
    // con lo que Keycloak realmente firma) y para /.well-known/oauth-protected-resource
    // (los clientes necesitan esa URL pública para el login, no la interna).
    @Value("${jde.mcp.security.keycloak-jwks-uri:}")
    private String keycloakJwksUri;

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

    @Value("${jde.atina.jwt.secret:}")
    private String atinaJwtSecret;

    @Value("${jde.atina.jwt.issuer:Issue}")
    private String atinaIssuer;

    // Vacío por defecto = no exige "aud" en tokens Atina (compat con lo que emite
    // hoy el microservicio, que no trae ese claim). Setear para exigirlo una vez
    // que Atina empiece a incluirlo en el token.
    @Value("${jde.atina.jwt.expected-audience:}")
    private String atinaExpectedAudience;

    private static final ObjectMapper CLAIMS_PEEKER = new ObjectMapper();

    /**
     * Decoder dual: acepta tokens de Keycloak (OAuth2/OIDC) y tokens emitidos
     * por el microservicio de Atina (HS256, secreto compartido). El ruteo se
     * hace mirando el claim "iss" del token, y cada rama valida firma completa.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        JwtDecoder keycloakDecoder = keycloakDecoder();
        JwtDecoder atinaDecoder = atinaDecoder();

        return token -> {
            if (atinaIssuer.equals(peekIssuer(token))) {
                if (atinaJwtSecret == null || atinaJwtSecret.isBlank()) {
                    throw new BadJwtException(
                            "Token de Atina recibido pero ATINA_JWT_SECRET no está configurado en el MCP Server.");
                }
                return atinaDecoder.decode(token);
            }
            return keycloakDecoder.decode(token);
        };
    }

    private JwtDecoder keycloakDecoder() {
        // SupplierJwtDecoder difiere el OIDC discovery/jwks-uri contra Keycloak hasta
        // el primer token a validar: el server (y los tests de contexto) arrancan
        // aunque Keycloak no este disponible en ese momento.
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder;
            if (keycloakJwksUri != null && !keycloakJwksUri.isBlank()) {
                // jwks-uri fijo (alcanzable desde este proceso), sin discovery contra
                // issuerUri -- ver comentario del campo keycloakJwksUri.
                decoder = NimbusJwtDecoder.withJwkSetUri(keycloakJwksUri).build();
            } else {
                decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
            }

            // Validado siempre contra issuerUri (el valor publico que Keycloak firma en
            // "iss"), tanto si el jwks vino por discovery como si vino de keycloakJwksUri.
            // Validador de audiencia: sin esto, CUALQUIER token valido de Keycloak
            // (de otro client, de otra realm-app) seria aceptado por este resource server.
            List<OAuth2TokenValidator<Jwt>> validators = List.of(
                    new JwtTimestampValidator(),
                    new JwtIssuerValidator(issuerUri),
                    audienceValidator(expectedAudience));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        });
    }

    private JwtDecoder atinaDecoder() {
        // Nota: los tokens de Atina no traen claim "exp"; el JwtTimestampValidator
        // solo valida si el claim está presente. La vigencia real de la sesión la
        // controla el propio microservicio (claim sessionId) en cada llamada.
        return new SupplierJwtDecoder(() -> {
            // ATINA_JWT_SECRET viaja en Base64 estándar (alfabeto +/, no el
            // URL-safe -_): el microservicio la decodifica con
            // javax.xml.bind.DatatypeConverter.parseBase64Binary(...), que es
            // Base64 RFC 4648 clásico. Hay que replicar exactamente ese decoder
            // o la clave HMAC resultante no coincide y la firma no valida.
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(atinaJwtSecret);
            } catch (IllegalArgumentException e) {
                throw new BadJwtException(
                        "ATINA_JWT_SECRET no es un valor Base64 válido.", e);
            }
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();

            List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>(List.of(
                    new JwtTimestampValidator(),
                    new JwtIssuerValidator(atinaIssuer)
            ));
            // Opt-in: hoy el token de Atina no trae "aud", así que por defecto
            // (property vacía) no se exige. Setear jde.atina.jwt.expected-audience
            // una vez que Atina empiece a emitir ese claim.
            if (!atinaExpectedAudience.isBlank()) {
                validators.add(audienceValidator(atinaExpectedAudience));
            }
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        });
    }

    /** Falla si "aud" no contiene el valor esperado. Compartido entre las dos ramas. */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return jwt -> jwt.getAudience().contains(expectedAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "Audiencia no coincide con " + expectedAudience, null));
    }

    /** Lee el claim "iss" sin validar firma, solo para decidir la rama de validación. */
    private String peekIssuer(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            return CLAIMS_PEEKER.readTree(payload).path("iss").asText(null);
        } catch (Exception e) {
            // Token malformado: que lo rechace el decoder de Keycloak con su error estándar
            return null;
        }
    }
}
