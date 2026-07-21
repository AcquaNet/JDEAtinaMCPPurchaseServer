package com.atina.jdeMCPServer.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Optional;

/**
 * Cliente de OpenBao (API KV v2). Lee dos tipos de secreto:
 *  - la credencial JDE desde {BAO_ADDR}/v1/secret/data/jde/{jdeUser}
 *    (campo obligatorio "password"; "user" opcional, default = nombre del path);
 *  - el token de sesión JDE de Atina desde
 *    {BAO_ADDR}/v1/secret/data/jde/atina/{sub} (campo "atina_token"), usado por
 *    la etapa 2 (ver {@link AtinaSessionVault}).
 */
@Service
public class OpenBaoCredentialVault implements CredentialVault, AtinaSessionVault {

    private static final Logger log = LoggerFactory.getLogger(OpenBaoCredentialVault.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baoAddr;
    private final String baoToken;

    public OpenBaoCredentialVault(
            @Value("${jde.vault.addr}") String baoAddr,
            @Value("${jde.vault.token}") String baoToken,
            ObjectMapper objectMapper) {

        this.webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofSeconds(10))
        )).build();
        this.objectMapper = objectMapper;
        this.baoAddr = baoAddr;
        this.baoToken = baoToken;
    }

    @Override
    public JdeCredential getCredential(String jdeUser) {

        if (baoToken == null || baoToken.isBlank()) {
            throw new VaultUnavailableException(
                    "BAO_TOKEN no está configurado en el entorno del MCP Server.");
        }

        String body;
        try {
            body = webClient.get()
                    .uri(baoAddr + "/v1/secret/data/jde/{user}", jdeUser)
                    .header("X-Vault-Token", baoToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new VaultCredentialNotFoundException(jdeUser);
            }
            throw new VaultUnavailableException(
                    "OpenBao respondió " + e.getStatusCode().value() +
                            " al leer secret/data/jde/" + jdeUser, e);
        } catch (Exception e) {
            throw new VaultUnavailableException(
                    "No se pudo conectar a OpenBao en " + baoAddr, e);
        }

        try {
            JsonNode data = objectMapper.readTree(body).path("data").path("data");
            String password = data.path("password").asText(null);
            if (password == null || password.isBlank()) {
                throw new VaultCredentialNotFoundException(jdeUser);
            }
            String user = data.path("user").asText(jdeUser);
            log.debug("Credencial obtenida del vault para usuario JDE [{}]", jdeUser);
            return new JdeCredential(user, password);
        } catch (VaultCredentialNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new VaultUnavailableException(
                    "Respuesta inesperada de OpenBao para secret/data/jde/" + jdeUser, e);
        }
    }

    @Override
    public Optional<String> getAtinaSessionToken(String key) {

        if (baoToken == null || baoToken.isBlank()) {
            throw new VaultUnavailableException(
                    "BAO_TOKEN no está configurado en el entorno del MCP Server.");
        }

        String body;
        try {
            body = webClient.get()
                    .uri(baoAddr + "/v1/secret/data/jde/atina/{key}", key)
                    .header("X-Vault-Token", baoToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            // 404 = no hay token guardado para esta clave: caso normal, se degrada
            // al siguiente origen (claim) según jde.atina.session-source.
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new VaultUnavailableException(
                    "OpenBao respondió " + e.getStatusCode().value() +
                            " al leer secret/data/jde/atina/" + key, e);
        } catch (Exception e) {
            throw new VaultUnavailableException(
                    "No se pudo conectar a OpenBao en " + baoAddr, e);
        }

        try {
            JsonNode data = objectMapper.readTree(body).path("data").path("data");
            String token = data.path("atina_token").asText(null);
            if (token == null || token.isBlank()) {
                return Optional.empty();
            }
            log.debug("Token de sesión Atina obtenido del vault para clave [{}]", key);
            return Optional.of(token);
        } catch (Exception e) {
            throw new VaultUnavailableException(
                    "Respuesta inesperada de OpenBao para secret/data/jde/atina/" + key, e);
        }
    }
}
