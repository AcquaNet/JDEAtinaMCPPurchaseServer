package com.atina.JDEmcpPur.services;

import com.atina.JDEmcpPur.auth.JdeAuthService;
import com.atina.JDEmcpPur.tools.JdePurchaseApprovalTool;
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
import java.time.Instant;

@Service
public class JdePurchaseOrderClient {

    private static final Logger log = LoggerFactory.getLogger(JdePurchaseOrderClient.class);

    private final WebClient webClient;
    private final JdeAuthService authService;
    private final String baseUrl;

    public JdePurchaseOrderClient(
            JdeAuthService authService,
            @Value("${jde.api.base-url}") String baseUrl) {

        this.webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMinutes(8))
        )).build();
        this.authService = authService;
        this.baseUrl = baseUrl;
    }

    public String getPendingPurchaseOrders(int limit) {

        String token = authService.getOrCreateToken();

        Instant startTime = Instant.now();

        ResponseEntity<String> response = webClient.get()
                .uri(baseUrl + "/v1/getPurchaseOrdersForApprover?limit={limit}", limit)
                .header("X-Approver-Token", token)
                .retrieve()
                .toEntity(String.class)
                .block();

        // 2. Registrar el tiempo de fin de la consulta
        Instant endTime = Instant.now();

        // 3. Calcular la duración en milisegundos y convertir a segundos
        long durationMillis = Duration.between(startTime, endTime).toMillis();
        double durationSeconds = durationMillis / 1000.0;
        String timeInfo = String.format("Query duration: %.2f seconds.", durationSeconds);
        log.info("JDE Pending Orders Query took: {} seconds", durationSeconds);

        authService.updateTokenFromResponse(response.getHeaders());

        return response.getBody();
    }

    public String processPurchaseOrder(String requestJson) {

        String token = authService.getOrCreateToken();

        ResponseEntity<String> response = webClient.post()
                .uri(baseUrl + "/v1/processPurchaseOrderApproveReject")
                .header("X-Approver-Token",  token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestJson)
                .retrieve()
                .toEntity(String.class)
                .block();

        authService.updateTokenFromResponse(response.getHeaders());

        return response.getBody();
    }

    public String getPurchaseOrderDetail(String documentOrderTypeCode,
                                         Integer documentOrderInvoiceNumber,
                                         String documentCompanyKeyOrderNo,
                                         String documentSuffix) {


        String token = authService.getOrCreateToken();

        ResponseEntity<String> response = webClient.get()
                .uri(
                        baseUrl
                                + "/v1/getPurchaseOrderDetailForApprover"
                                + "?documentCompanyKeyOrderNo={company}"
                                + "&documentOrderTypeCode={type}"
                                + "&documentOrderInvoiceNumber={number}"
                                + "&documentSuffix={suffix}",
                        documentCompanyKeyOrderNo,
                        documentOrderTypeCode,
                        documentOrderInvoiceNumber,
                        documentSuffix
                )
                .header("X-Approver-Token",  token)
                .retrieve()
                .toEntity(String.class)
                .block();

        authService.updateTokenFromResponse(response.getHeaders());

        return response.getBody();

    }
}
