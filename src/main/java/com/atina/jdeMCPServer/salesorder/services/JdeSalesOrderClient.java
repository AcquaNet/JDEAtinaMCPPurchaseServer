package com.atina.jdeMCPServer.salesorder.services;

import com.atina.jdeMCPServer.auth.JdeAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Service
public class JdeSalesOrderClient {

    private static final Logger log = LoggerFactory.getLogger(JdeSalesOrderClient.class);
    private final WebClient webClient;
    private final JdeAuthService authService;
    private final String baseUrl;

    public JdeSalesOrderClient(
            JdeAuthService authService,
            @Value("${jde.so.api.base-url}") String baseUrl) {

        this.webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMinutes(10))
        )).build();
        this.authService = authService;
        this.baseUrl = baseUrl;
    }

    public String getCustomerCreditFinancialInfo(int customerNumber) {

        String token = authService.getOrCreateToken();

        log.info("Requesting credit/financial info for customer {}", customerNumber);

        ResponseEntity<String> response = webClient.get()
                .uri(baseUrl + "/v1/getCustomerCreditFinancialInfo?customerNumber={customerNumber}", customerNumber)
                .header("X-Approver-Token", token)
                .retrieve()
                .toEntity(String.class)
                .block();

        authService.updateTokenFromResponse(response.getHeaders());

        log.info("Credit/financial info for customer {} Done", customerNumber);

        return response.getBody();
    }
}
