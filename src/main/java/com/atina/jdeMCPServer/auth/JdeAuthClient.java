package com.atina.jdeMCPServer.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;

@Service
public class JdeAuthClient {

    private static final Logger log = LoggerFactory.getLogger(JdeAuthClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public JdeAuthClient(
            @Value("${jde.api.base-url}") String baseUrl,
            @Value("${jde.api.login-timeout-minutes:5}") int loginTimeoutMinutes,
            ObjectMapper objectMapper) {

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(loginTimeoutMinutes))
                ))
                .build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public LoginResult login(String user, String password) {

        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(Map.of(
                    "user",        user.toUpperCase(),
                    "password",    password,
                    "environment", "JDV920",
                    "role",        "*ALL"
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize login request", e);
        }

        ResponseEntity<String> response = webClient.post()
                .uri(baseUrl + "/v1/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestJson)
                .retrieve()
                .toEntity(String.class)
                .block();

        String token = response.getHeaders().getFirst("X-Approver-Token");
        if (token == null || token.isBlank()) {
            throw new RuntimeException(
                    "Mulesoft did not return X-Approver-Token on login response"
            );
        }

        String expiresAt = null;
        try {
            JsonNode node = objectMapper.readTree(response.getBody());
            expiresAt = node.path("expiresAt").asText(null);
        } catch (Exception e) {
            log.warn("Could not parse login response body: {}", e.getMessage());
        }

        return new LoginResult(token, expiresAt);
    }

    public record LoginResult(String token, String expiresAt) {}
}