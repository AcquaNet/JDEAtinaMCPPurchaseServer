package com.atina.jdeMCPServer.logs;

import com.atina.jdeMCPServer.auth.JdeAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Llama a POST /v1/logs/capture del Gateway de Atina -- a diferencia de
 * /v1/operations/execute (operaciones BSSV de negocio), este endpoint
 * devuelve texto plano: el propio log del Gateway (microserviceLog=false) o
 * el del microservicio conector JDE (microserviceLog=true), ya filtrado por
 * correlationUUID del lado del Gateway.
 *
 * Timeout corto y propio (no jde.atina.gateway.timeout-minutes, pensado para
 * operaciones BSSV que pueden tardar minutos): esto es una consulta de
 * diagnostico, tiene que ser rapida o fallar rapido.
 */
@Component
public class AtinaLogCaptureClient {

    private static final Logger log = LoggerFactory.getLogger(AtinaLogCaptureClient.class);

    private final WebClient gatewayWebClient;
    private final JdeAuthService authService;
    private final String gatewayBaseUrl;

    public AtinaLogCaptureClient(
            JdeAuthService authService,
            @Value("${jde.atina.gateway.base-url}") String gatewayBaseUrl,
            @Value("${jde.atina.gateway.logs-capture-timeout-seconds:30}") long timeoutSeconds) {
        this.authService = authService;
        this.gatewayBaseUrl = gatewayBaseUrl;
        // Default de WebClient (256KB) es chico para esto -- el log del microservicio
        // conector JDE (GetMetadaParaOperacion + request/response completos) puede
        // superarlo facil en una sola captura.
        this.gatewayWebClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(timeoutSeconds))))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build())
                .build();
    }

    /**
     * Captura el log del Gateway y despues el del microservicio, en ese orden,
     * para el correlationId dado -- nunca lanza: una falla en cualquiera de
     * las dos llamadas queda anotada inline en el resultado en vez de romper
     * el resto de la respuesta de /admin/logs.
     */
    public List<String> captureGatewayAndMicroserviceLogs(String correlationId) {
        List<String> result = new ArrayList<>();
        result.addAll(captureSection(correlationId, false, "Gateway"));
        result.addAll(captureSection(correlationId, true, "Microservicio"));
        return result;
    }

    private List<String> captureSection(String correlationId, boolean microserviceLog, String label) {
        List<String> section = new ArrayList<>();
        section.add("===== " + label + " (microserviceLog=" + microserviceLog + ") =====");
        try {
            String raw = capture(correlationId, microserviceLog);
            if (raw == null || raw.isBlank()) {
                section.add("(sin líneas para este correlationId)");
            } else {
                section.addAll(raw.lines().toList());
            }
        } catch (Exception e) {
            log.error("Error capturando log de {} para correlationId {}", label, correlationId, e);
            section.add("ERROR al capturar el log de " + label + ": " + e.getMessage());
        }
        return section;
    }

    private String capture(String correlationId, boolean microserviceLog) {
        String token = authService.getOrCreateToken();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionID", 0);
        body.put("correlationUUID", correlationId);
        body.put("microserviceLog", microserviceLog);

        return gatewayWebClient.post()
                .uri(gatewayBaseUrl + "/v1/logs/capture")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Token", "null")
                .header("TransactionId", "0")
                .header("correlationUUID", correlationId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
