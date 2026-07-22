package com.atina.jdeMCPServer.salesorder.services;

import com.atina.jdeMCPServer.auth.JdeAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class JdeSalesOrderClient {

    private static final Logger log = LoggerFactory.getLogger(JdeSalesOrderClient.class);

    // BSSV operations expuestas por el Gateway de Atina (endpoint /v1/operations/execute).
    private static final String OP_LOOKUP_ADDRESS_BOOK =
            "oracle.e1.bssv.JP010000.AddressBookManager.lookupAddressBook";
    private static final String OP_GET_CUSTOMER =
            "oracle.e1.bssv.JP010020.CustomerManager.getCustomerV3";

    private final WebClient webClient;
    private final WebClient gatewayWebClient;
    private final JdeAuthService authService;
    private final String baseUrl;
    private final String gatewayBaseUrl;

    public JdeSalesOrderClient(
            JdeAuthService authService,
            @Value("${jde.so.api.base-url}") String baseUrl,
            @Value("${jde.atina.gateway.base-url}") String gatewayBaseUrl,
            @Value("${jde.atina.gateway.timeout-minutes:10}") int gatewayTimeoutMinutes) {

        this.webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMinutes(10))
        )).build();
        this.gatewayWebClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMinutes(gatewayTimeoutMinutes))
        )).build();
        this.authService = authService;
        this.baseUrl = baseUrl;
        this.gatewayBaseUrl = gatewayBaseUrl;
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

    // =====================================================================
    // Gateway de Atina (localhost:8086) - operaciones BSSV genéricas.
    // A diferencia de los otros endpoints, el Gateway recibe el token de
    // sesión JDE en la cabecera "Authorization: Bearer <token>" y expone
    // una única operación POST /v1/operations/execute parametrizada por
    // "operacionKey" + "listaDeValores".
    // =====================================================================

    /**
     * Consulta de cliente por nombre: recupera los Address Book (AB Number)
     * cuyo nombre contiene {@code entityName}. Es la base para obtener el ID
     * del cliente que luego consumen el detalle y el precio/disponibilidad.
     */
    public String lookupAddressBookByName(String entityName) {

        log.info("Gateway lookupAddressBook by name '{}'", entityName);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("entityName", entityName);
        // Tipo de entidad C = Cliente (Customer). El backend espera el nombre
        // de campo exactamente como está (tal cual la operación BSSV del Gateway).
        value.put("enityTypeCode", "C");

        return executeGatewayOperation(OP_LOOKUP_ADDRESS_BOOK, value);
    }

    /**
     * Consulta del detalle de un cliente por su AB Number (entityId):
     * dirección, importes, instrucciones de facturación, company, crédito,
     * tax id, etc.
     */
    public String getCustomerDetail(int entityId) {

        log.info("Gateway getCustomer detail for entityId {}", entityId);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("entityId", entityId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("entity", entity);

        return executeGatewayOperation(OP_GET_CUSTOMER, value);
    }

    /**
     * Invoca una operación BSSV del Gateway de Atina. El token de sesión JDE
     * (mismo que resuelve {@link JdeAuthService#getOrCreateToken()}) viaja como
     * Bearer. El Gateway devuelve un token renovado en el cuerpo (campo
     * "jwtToken"), no en cabeceras; la estrategia de token de Atina no se
     * refresca, así que aquí solo se propaga el Bearer de la sesión vigente.
     */
    private String executeGatewayOperation(String operacionKey, Map<String, Object> value) {

        String token = authService.getOrCreateToken();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operacionKey", operacionKey);
        body.put("listaDeValores", List.of(value));
        body.put("connectorName", "WS");

        ResponseEntity<String> response = gatewayWebClient.post()
                .uri(gatewayBaseUrl + "/v1/operations/execute")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Token", "null")
                .header("TransactionId", "0")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .block();

        authService.updateTokenFromResponse(response.getHeaders());

        return response.getBody();
    }
}
