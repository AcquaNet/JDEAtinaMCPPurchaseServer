package com.atina.jdeMCPServer.salesorder.services;

import com.atina.jdeMCPServer.auth.JdeAuthService;
import com.atina.jdeMCPServer.gateway.RequestCoalescer;
import com.atina.jdeMCPServer.salesorder.model.CustomerAddress;
import com.atina.jdeMCPServer.salesorder.model.CustomerCreditInfo;
import com.atina.jdeMCPServer.salesorder.model.CustomerDetail;
import com.atina.jdeMCPServer.salesorder.model.CustomerSummary;
import com.atina.jdeMCPServer.salesorder.model.ItemSummary;
import com.atina.jdeMCPServer.salesorder.model.PriceQuote;
import com.atina.jdeMCPServer.salesorder.model.WarehouseAvailability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
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
    private static final String OP_ITEM_SEARCH =
            "oracle.e1.bssv.JP410000.InventoryManager.getItemSearch";
    private static final String OP_GET_ITEM_PRICE_AVAILABILITY =
            "oracle.e1.bssv.JP420000.SalesOrderManager.getItemPriceAndAvailabilityV3";
    private static final String OP_GET_CUSTOMER_ITEM_PRICE =
            "oracle.e1.bssv.JP420000.SalesOrderManager.getCustomerItemPrice";

    // Mismo default (10) que usa JdePurchaseOrderClient.limitAndFormatPendingOrders para
    // ordenes de compra pendientes -- aplicado aca a busquedas de items/clientes por el
    // mismo motivo: el microservicio ya trae los resultados con su propio limite, pero sin
    // recortar mas el LLM puede recibir mas filas de las que necesita para razonar.
    private static final int DEFAULT_RESULT_LIMIT = 10;

    /**
     * Recorta una lista ya recuperada a lo sumo a {@code limit} elementos (default
     * DEFAULT_RESULT_LIMIT si es null o <= 0). No vuelve a consultar el backend --
     * pensado para aplicarse sobre datos ya resueltos (incluso cacheados por
     * LongRunningTaskRegistry), no como parametro de la request al Gateway.
     */
    public static <T> List<T> applyLimit(List<T> items, Integer limit) {
        int effectiveLimit = (limit != null && limit > 0) ? limit : DEFAULT_RESULT_LIMIT;
        return items.size() > effectiveLimit ? items.subList(0, effectiveLimit) : items;
    }

    private final WebClient webClient;
    private final WebClient gatewayWebClient;
    private final JdeAuthService authService;
    private final ObjectMapper objectMapper;
    private final RequestCoalescer requestCoalescer;
    private final String baseUrl;
    private final String gatewayBaseUrl;
    private final String gatewayTransactionId;

    public JdeSalesOrderClient(
            JdeAuthService authService,
            ObjectMapper objectMapper,
            RequestCoalescer requestCoalescer,
            @Value("${jde.so.api.base-url}") String baseUrl,
            @Value("${jde.atina.gateway.base-url}") String gatewayBaseUrl,
            @Value("${jde.atina.gateway.timeout-minutes:10}") int gatewayTimeoutMinutes,
            @Value("${jde.atina.gateway.transaction-id:0}") String gatewayTransactionId) {

        this.webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMinutes(10))
        )).build();
        this.gatewayWebClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMinutes(gatewayTimeoutMinutes))
        )).build();
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.requestCoalescer = requestCoalescer;
        this.baseUrl = baseUrl;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.gatewayTransactionId = gatewayTransactionId;
    }

    // Coalescea llamadas identicas concurrentes (mismo customerNumber): si un cliente
    // MCP cancela por timeout y reintenta la misma tool call mientras la anterior
    // sigue esperando a Mulesoft, el reintento espera el resultado en curso en vez
    // de disparar una llamada nueva.
    public String getCustomerCreditFinancialInfo(int customerNumber) {
        return requestCoalescer.execute(
                "getCustomerCreditFinancialInfo|" + customerNumber,
                () -> doGetCustomerCreditFinancialInfo(customerNumber));
    }

    private String doGetCustomerCreditFinancialInfo(int customerNumber) {

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
    public List<CustomerSummary> lookupAddressBookByName(String entityName) {

        log.info("Gateway lookupAddressBook by name '{}'", entityName);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("entityName", entityName);
        // Tipo de entidad C = Cliente (Customer). El backend espera el nombre
        // de campo exactamente como está (tal cual la operación BSSV del Gateway).
        value.put("enityTypeCode", "C");

        String raw = executeGatewayOperation(OP_LOOKUP_ADDRESS_BOOK, value);
        JsonNode listaDeValores = parseListaDeValores(raw, OP_LOOKUP_ADDRESS_BOOK);
        JsonNode results = listaDeValores.path("lookupAddressBookResult");

        List<CustomerSummary> customers = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode item : results) {
                customers.add(new CustomerSummary(
                        item.path("entityName").asText("").trim(),
                        item.path("entity").path("entityId").asInt()));
            }
        }
        return customers;
    }

    /**
     * Consulta del detalle de un cliente por su AB Number (entityId):
     * dirección, importes, instrucciones de facturación, company, crédito,
     * tax id, etc.
     */
    public CustomerDetail getCustomerDetail(int entityId) {

        log.info("Gateway getCustomer detail for entityId {}", entityId);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("entityId", entityId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("entity", entity);

        String raw = executeGatewayOperation(OP_GET_CUSTOMER, value);
        JsonNode listaDeValores = parseListaDeValores(raw, OP_GET_CUSTOMER);
        JsonNode customer = listaDeValores.path("customerResults").path(0);

        CustomerAddress address = parseCustomerAddress(customer.path("address"));
        CustomerCreditInfo credit = parseCustomerCreditInfo(customer);

        return new CustomerDetail(
                customer.path("entity").path("entityId").asInt(),
                customer.path("entityName").asText("").trim(),
                customer.path("entity").path("entityTaxId").asText("").trim(),
                customer.path("company").asText("").trim(),
                customer.path("invoice").path("currencyCode").asText("").trim(),
                customer.path("languageCode").asText("").trim(),
                address,
                credit);
    }

    private static CustomerAddress parseCustomerAddress(JsonNode addressNode) {
        return new CustomerAddress(
                addressNode.path("addressLine1").asText("").trim(),
                addressNode.path("addressLine2").asText("").trim(),
                addressNode.path("addressLine3").asText("").trim(),
                addressNode.path("addressLine4").asText("").trim(),
                addressNode.path("city").asText("").trim(),
                addressNode.path("stateCode").asText("").trim(),
                addressNode.path("postalCode").asText("").trim(),
                addressNode.path("countryCode").asText("").trim());
    }

    private static CustomerCreditInfo parseCustomerCreditInfo(JsonNode customer) {
        // El campo de credit limit se vio documentado en dos ubicaciones posibles
        // segun la version de la operacion BSSV -- se intenta amounts.* primero y
        // se cae a credit.* si no esta.
        JsonNode creditLimitNode = customer.path("amounts").path("amountCreditLimit");
        if (creditLimitNode.isMissingNode() || creditLimitNode.isNull()) {
            creditLimitNode = customer.path("credit").path("amountCreditLimit");
        }

        return new CustomerCreditInfo(
                creditLimitNode.decimalValue(),
                customer.path("amounts").path("amountOpen").decimalValue(),
                customer.path("amounts").path("amountDue").decimalValue(),
                customer.path("credit").path("creditManagerCode").asText("").trim(),
                customer.path("billingInstructions").path("creditCheckLevelCode").asText("").trim(),
                customer.path("billingInstructions").path("holdCode").asText("").trim());
    }

    /**
     * Búsqueda de artículos por nombre (o fragmento de nombre): devuelve los
     * itemId candidatos que luego consume el precio/disponibilidad.
     */
    public List<ItemSummary> searchItems(String itemSearchText) {
        log.info("Gateway item search for text '{}'", itemSearchText);
        String raw = executeGatewayOperation(OP_ITEM_SEARCH, itemSearchValue(itemSearchText));
        return parseItemSearchResults(raw);
    }

    /**
     * Resuelve el token de sesión JDE actual -- pensado para ejecuciones que
     * necesitan pasarlo como dato plano fuera del ciclo de vida del
     * HttpServletRequest original (ver {@link #searchItemsWithToken}).
     */
    public String resolveSessionToken() {
        return authService.getOrCreateToken();
    }

    /**
     * Igual que {@link #searchItems} pero con un token ya resuelto (no lo
     * resuelve internamente vía authService, que depende de
     * RequestContextHolder -- ver {@link #executeGatewayOperationWithToken}).
     */
    public List<ItemSummary> searchItemsWithToken(String itemSearchText, String token) {
        String raw = executeGatewayOperationWithToken(OP_ITEM_SEARCH, itemSearchValue(itemSearchText), token);
        return parseItemSearchResults(raw);
    }

    private static Map<String, Object> itemSearchValue(String itemSearchText) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("itemSearchText", itemSearchText);
        return value;
    }

    private List<ItemSummary> parseItemSearchResults(String rawJson) {
        JsonNode listaDeValores = parseListaDeValores(rawJson, OP_ITEM_SEARCH);
        JsonNode results = listaDeValores.path("itemSearchDetails");

        List<ItemSummary> items = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode item : results) {
                items.add(new ItemSummary(
                        item.path("itemCatalog").asInt(),
                        item.path("itemDescription1").asText("").trim()));
            }
        }
        return items;
    }

    /**
     * Precio y disponibilidad por almacén de un artículo para un cliente
     * (operación BSSV getItemPriceAndAvailabilityV3).
     */
    public PriceQuote getItemPriceAndAvailability(int itemId, String businessUnit, int entityId,
                                                   String currencyCode, Number quantity,
                                                   String unitOfMeasure, String processingVersion) {

        log.info("Gateway item price+availability for itemId {} / entityId {}", itemId, entityId);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", itemId);

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("item", item);
        product.put("quantityOrdered", quantity);
        product.put("unitOfMeasureCodeTransaction", unitOfMeasure);

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("entityId", entityId);

        Map<String, Object> processing = new LinkedHashMap<>();
        processing.put("processingVersion", processingVersion);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("product", product);
        value.put("businessUnit", businessUnit);
        value.put("customer", customer);
        value.put("currencyCode", currencyCode);
        value.put("processing", processing);

        String raw = executeGatewayOperation(OP_GET_ITEM_PRICE_AVAILABILITY, value);
        JsonNode listaDeValores = parseListaDeValores(raw, OP_GET_ITEM_PRICE_AVAILABILITY);
        JsonNode productResult = listaDeValores.path("product");

        List<WarehouseAvailability> availability = new ArrayList<>();
        JsonNode availabilityNode = productResult.path("availability");
        if (availabilityNode.isArray()) {
            for (JsonNode row : availabilityNode) {
                JsonNode warehouse = row.path("warehouse");
                availability.add(new WarehouseAvailability(
                        warehouse.path("warehouse").asText("").trim(),
                        warehouse.path("address").path("mailingName").asText("").trim(),
                        row.path("quantityAvailable").decimalValue()));
            }
        }

        return new PriceQuote(
                productResult.path("priceUnit").decimalValue(),
                productResult.path("priceExtended").decimalValue(),
                availability);
    }

    /**
     * Precio de un artículo para un cliente, sin disponibilidad (operación
     * BSSV getCustomerItemPrice).
     */
    public PriceQuote getCustomerItemPrice(int itemId, String businessUnit, int entityId,
                                            String currencyCode, Number quantity,
                                            String unitOfMeasure, String processingVersion) {

        log.info("Gateway customer item price for itemId {} / entityId {}", itemId, entityId);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", itemId);

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("item", item);
        product.put("businessUnit", businessUnit);

        Map<String, Object> shipTo = new LinkedHashMap<>();
        shipTo.put("entityId", entityId);

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("shipTo", shipTo);

        Map<String, Object> processing = new LinkedHashMap<>();
        processing.put("processingVersion", processingVersion);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("product", product);
        value.put("transactionQuantity", quantity);
        value.put("unitOfMeasureCodeTransaction", unitOfMeasure);
        value.put("currencyCode", currencyCode);
        value.put("customer", customer);
        value.put("businessUnit", businessUnit);
        value.put("processing", processing);

        String raw = executeGatewayOperation(OP_GET_CUSTOMER_ITEM_PRICE, value);
        JsonNode listaDeValores = parseListaDeValores(raw, OP_GET_CUSTOMER_ITEM_PRICE);

        return new PriceQuote(
                listaDeValores.path("priceUnitDomestic").decimalValue(),
                listaDeValores.path("priceExtendedDomestic").decimalValue(),
                List.of());
    }

    /**
     * Invoca una operación BSSV del Gateway de Atina. El token de sesión JDE
     * (mismo que resuelve {@link JdeAuthService#getOrCreateToken()}) viaja como
     * Bearer. El Gateway devuelve un token renovado en el cuerpo (campo
     * "jwtToken"), no en cabeceras; la estrategia de token de Atina no se
     * refresca, así que aquí solo se propaga el Bearer de la sesión vigente.
     */
    private String executeGatewayOperation(String operacionKey, Map<String, Object> value) {
        return requestCoalescer.execute(
                coalesceKey(operacionKey, value),
                () -> doExecuteGatewayOperation(operacionKey, value));
    }

    /**
     * Variante de {@link #executeGatewayOperation(String, Map)} con un token ya
     * resuelto -- pensada para ejecuciones que corren fuera del ciclo de vida
     * del HttpServletRequest original (ver {@link #resolveSessionToken()}),
     * donde authService no tiene RequestContextHolder disponible. No llama
     * authService.updateTokenFromResponse(...) por el mismo motivo.
     */
    private String executeGatewayOperationWithToken(String operacionKey, Map<String, Object> value, String token) {
        return requestCoalescer.execute(
                coalesceKey(operacionKey, value),
                () -> postToGateway(operacionKey, value, token).getBody());
    }

    private JsonNode parseListaDeValores(String rawJson, String operacionKey) {
        try {
            return objectMapper.readTree(rawJson).path("listaDeValores");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Respuesta inválida del Gateway de Atina para la operación " + operacionKey, e);
        }
    }

    private String coalesceKey(String operacionKey, Map<String, Object> value) {
        try {
            return operacionKey + "|" + objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return operacionKey + "|" + value;
        }
    }

    private String doExecuteGatewayOperation(String operacionKey, Map<String, Object> value) {
        String token = authService.getOrCreateToken();
        ResponseEntity<String> response = postToGateway(operacionKey, value, token);
        authService.updateTokenFromResponse(response.getHeaders());
        return response.getBody();
    }

    private ResponseEntity<String> postToGateway(String operacionKey, Map<String, Object> value, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operacionKey", operacionKey);
        body.put("listaDeValores", List.of(value));
        body.put("connectorName", "WS");

        return gatewayWebClient.post()
                .uri(gatewayBaseUrl + "/v1/operations/execute")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Token", "null")
                .header("TransactionId", gatewayTransactionId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .block();
    }
}
