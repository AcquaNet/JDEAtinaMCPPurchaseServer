package com.atina.JDEmcpPur.services;

import com.atina.JDEmcpPur.auth.JdeAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class JdePurchaseOrderClient {

    private final WebClient webClient;
    private final JdeAuthService authService;
    private final String baseUrl;

    public JdePurchaseOrderClient(
            JdeAuthService authService,
            @Value("${jde.api.base-url}") String baseUrl) {

        this.webClient = WebClient.builder().build();
        this.authService = authService;
        this.baseUrl = baseUrl;
    }

    public String getPendingPurchaseOrders(int limit) {

        String token = authService.getOrCreateToken();

        ResponseEntity<String> response = webClient.get()
                .uri(baseUrl + "/v1/getPurchaseOrdersForApprover?limit={limit}", limit)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toEntity(String.class)
                .block();

        authService.updateTokenFromResponse(response.getHeaders());

        return response.getBody();
    }

    public String processPurchaseOrder(String requestJson) {

        String token = authService.getOrCreateToken();

        ResponseEntity<String> response = webClient.post()
                .uri(baseUrl + "/v1/processPurchaseOrderApproveReject")
                .header("Authorization", "Bearer " + token)
                .bodyValue(requestJson)
                .retrieve()
                .toEntity(String.class)
                .block();

        authService.updateTokenFromResponse(response.getHeaders());

        return response.getBody();
    }

}
