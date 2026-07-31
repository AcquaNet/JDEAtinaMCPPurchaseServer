package com.atina.jdeMCPServer.salesorder.services;

import com.atina.jdeMCPServer.auth.JdeAuthService;
import com.atina.jdeMCPServer.gateway.RequestCoalescer;
import com.atina.jdeMCPServer.mcp.CorrelationIdContext;
import com.atina.jdeMCPServer.salesorder.model.CustomerAddress;
import com.atina.jdeMCPServer.salesorder.model.CustomerCreditInfo;
import com.atina.jdeMCPServer.salesorder.model.CustomerCreditSummary;
import com.atina.jdeMCPServer.salesorder.model.CustomerDetail;
import com.atina.jdeMCPServer.salesorder.model.CustomerSummary;
import com.atina.jdeMCPServer.salesorder.model.ItemListPriceEntry;
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
    private static final String OP_GET_CUSTOMER_CREDIT_INFO =
            "oracle.e1.bssv.JP010020.CustomerManager.getCustomerCreditInformationV2";
    private static final String OP_GET_ITEM_LIST_PRICE =
            "oracle.e1.bssv.JP420000.SalesOrderManager.getItemListPrice";

    // businessUnit va con padding de espacios a la izquierda hasta 12 caracteres --
    // mismo requisito que confirmado en JdePurchaseOrderClient para otras operaciones
    // BSSV; sin este padding getItemListPrice no matchea el almacen.
    private static final int BUSINESS_UNIT_FIELD_WIDTH = 12;

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

    private final WebClient gatewayWebClient;
    private final JdeAuthService authService;
    private final ObjectMapper objectMapper;
    private final RequestCoalescer requestCoalescer;
    private final String gatewayBaseUrl;
    private final String gatewayTransactionId;
    private final CorrelationIdContext correlationIdContext;

    public JdeSalesOrderClient(
            JdeAuthService authService,
            ObjectMapper objectMapper,
            RequestCoalescer requestCoalescer,
            @Value("${jde.atina.gateway.base-url}") String gatewayBaseUrl,
            @Value("${jde.atina.gateway.timeout-minutes:10}") int gatewayTimeoutMinutes,
            @Value("${jde.atina.gateway.transaction-id:0}") String gatewayTransactionId,
            CorrelationIdContext correlationIdContext) {

        this.gatewayWebClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMinutes(gatewayTimeoutMinutes))
        )).build();
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.requestCoalescer = requestCoalescer;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.gatewayTransactionId = gatewayTransactionId;
        this.correlationIdContext = correlationIdContext;
    }

    /**
     * Crédito y exposición financiera de un cliente (operación BSSV
     * getCustomerCreditInformationV2). Devuelve solo los campos que consume
     * jde_get_customer_credit_info -- el resto de la respuesta (e1MessageList,
     * customerNumberGLN, entityLongId, etc.) no se expone.
     */
    public CustomerCreditSummary getCustomerCreditInfo(int entityId) {
        String raw = executeGatewayOperation(OP_GET_CUSTOMER_CREDIT_INFO, customerCreditInfoValue(entityId));
        return parseCustomerCreditSummary(raw);
    }

    /**
     * Igual que {@link #getCustomerCreditInfo} pero con un token ya resuelto
     * (no lo resuelve internamente vía authService, que depende de
     * RequestContextHolder -- ver {@link #executeGatewayOperationWithToken}).
     */
    public CustomerCreditSummary getCustomerCreditInfoWithToken(int entityId, String token) {
        String raw = executeGatewayOperationWithToken(OP_GET_CUSTOMER_CREDIT_INFO, customerCreditInfoValue(entityId), token);
        return parseCustomerCreditSummary(raw);
    }

    private static Map<String, Object> customerCreditInfoValue(int entityId) {
        log.info("Gateway customer credit info for entityId {}", entityId);
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("entityId", entityId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("entity", entity);
        return value;
    }

    private CustomerCreditSummary parseCustomerCreditSummary(String rawJson) {
        JsonNode result = parseListaDeValores(rawJson, OP_GET_CUSTOMER_CREDIT_INFO);

        return new CustomerCreditSummary(
                result.path("amountCreditLimit").decimalValue(),
                result.path("amountTotalExposure").decimalValue(),
                result.path("creditHoldExempt").asBoolean(),
                result.path("entity").path("entityTaxId").asText("").trim());
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
        String raw = executeGatewayOperation(OP_LOOKUP_ADDRESS_BOOK, lookupAddressBookValue(entityName));
        return parseLookupAddressBookResults(raw);
    }

    /**
     * Igual que {@link #lookupAddressBookByName} pero con un token ya resuelto
     * (no lo resuelve internamente vía authService, que depende de
     * RequestContextHolder -- ver {@link #executeGatewayOperationWithToken}).
     */
    public List<CustomerSummary> lookupAddressBookByNameWithToken(String entityName, String token) {
        String raw = executeGatewayOperationWithToken(OP_LOOKUP_ADDRESS_BOOK, lookupAddressBookValue(entityName), token);
        return parseLookupAddressBookResults(raw);
    }

    private static Map<String, Object> lookupAddressBookValue(String entityName) {
        log.info("Gateway lookupAddressBook by name '{}'", entityName);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("entityName", entityName);
        // Tipo de entidad C = Cliente (Customer). El backend espera el nombre
        // de campo exactamente como está (tal cual la operación BSSV del Gateway).
        value.put("enityTypeCode", "C");
        return value;
    }

    private List<CustomerSummary> parseLookupAddressBookResults(String rawJson) {
        JsonNode listaDeValores = parseListaDeValores(rawJson, OP_LOOKUP_ADDRESS_BOOK);
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
        String raw = executeGatewayOperation(OP_GET_CUSTOMER, customerDetailValue(entityId));
        return parseCustomerDetail(raw);
    }

    /**
     * Igual que {@link #getCustomerDetail} pero con un token ya resuelto (no lo
     * resuelve internamente vía authService, que depende de
     * RequestContextHolder -- ver {@link #executeGatewayOperationWithToken}).
     */
    public CustomerDetail getCustomerDetailWithToken(int entityId, String token) {
        String raw = executeGatewayOperationWithToken(OP_GET_CUSTOMER, customerDetailValue(entityId), token);
        return parseCustomerDetail(raw);
    }

    private static Map<String, Object> customerDetailValue(int entityId) {
        log.info("Gateway getCustomer detail for entityId {}", entityId);
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("entityId", entityId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("entity", entity);
        return value;
    }

    private CustomerDetail parseCustomerDetail(String rawJson) {
        JsonNode listaDeValores = parseListaDeValores(rawJson, OP_GET_CUSTOMER);
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
     * (operación BSSV getItemPriceAndAvailabilityV3). El item se identifica
     * por itemId y/o itemCatalog -- al menos uno de los dos debe venir
     * informado (lo valida la tool antes de llamar acá).
     */
    public PriceQuote getItemPriceAndAvailability(Integer itemId, String itemCatalog, String businessUnit, int entityId,
                                                   String currencyCode, Number quantity,
                                                   String unitOfMeasure, String processingVersion) {

        log.info("Gateway item price+availability for itemId {} / itemCatalog {} / entityId {}",
                itemId, itemCatalog, entityId);

        Map<String, Object> item = buildItemMap(itemId, itemCatalog);

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
     * BSSV getCustomerItemPrice). El item se identifica por itemId y/o
     * itemCatalog -- al menos uno de los dos debe venir informado (lo valida
     * la tool antes de llamar acá).
     */
    public PriceQuote getCustomerItemPrice(Integer itemId, String itemCatalog, String businessUnit, int entityId,
                                            String currencyCode, Number quantity,
                                            String unitOfMeasure, String processingVersion) {

        log.info("Gateway customer item price for itemId {} / itemCatalog {} / entityId {}",
                itemId, itemCatalog, entityId);

        Map<String, Object> item = buildItemMap(itemId, itemCatalog);

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
     * Precio de lista de un artículo (operación BSSV getItemListPrice) --
     * a diferencia de {@link #getItemPriceAndAvailability} / {@link #getCustomerItemPrice},
     * no requiere cliente (entityId). Si no se informa businessUnit, la
     * respuesta trae un precio por cada almacén. El item se identifica por
     * itemId y/o itemCatalog -- al menos uno de los dos debe venir informado
     * (lo valida la tool antes de llamar acá), y si ambos vienen se mandan
     * los dos, como en el ejemplo de la operación BSSV.
     */
    public List<ItemListPriceEntry> getItemListPrice(Integer itemId, String itemCatalog, String businessUnit,
                                                       String currencyCode, String unitOfMeasureCode) {
        log.info("Gateway item list price for itemId {} / itemCatalog {} (businessUnit={})",
                itemId, itemCatalog, businessUnit);
        String raw = executeGatewayOperation(OP_GET_ITEM_LIST_PRICE,
                itemListPriceValue(itemId, itemCatalog, businessUnit, currencyCode, unitOfMeasureCode));
        return parseItemListPrice(raw);
    }

    /**
     * Igual que {@link #getItemListPrice} pero con un token ya resuelto (no lo
     * resuelve internamente vía authService, que depende de
     * RequestContextHolder -- ver {@link #executeGatewayOperationWithToken}).
     */
    public List<ItemListPriceEntry> getItemListPriceWithToken(Integer itemId, String itemCatalog, String businessUnit,
                                                                String currencyCode, String unitOfMeasureCode,
                                                                String token) {
        String raw = executeGatewayOperationWithToken(OP_GET_ITEM_LIST_PRICE,
                itemListPriceValue(itemId, itemCatalog, businessUnit, currencyCode, unitOfMeasureCode), token);
        return parseItemListPrice(raw);
    }

    /**
     * Objeto "item" común a las tres operaciones de precio (getItemListPrice,
     * getItemPriceAndAvailabilityV3, getCustomerItemPrice): identifica el
     * artículo por itemId y/o itemCatalog, incluyendo solo las claves
     * informadas (si vienen los dos, se mandan los dos).
     */
    private static Map<String, Object> buildItemMap(Integer itemId, String itemCatalog) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (itemId != null) {
            item.put("itemId", itemId);
        }
        if (itemCatalog != null && !itemCatalog.isBlank()) {
            item.put("itemCatalog", itemCatalog);
        }
        return item;
    }

    private static Map<String, Object> itemListPriceValue(Integer itemId, String itemCatalog, String businessUnit,
                                                            String currencyCode, String unitOfMeasureCode) {
        Map<String, Object> item = buildItemMap(itemId, itemCatalog);

        Map<String, Object> value = new LinkedHashMap<>();
        // businessUnit/currencyCode/unitOfMeasureCode se omiten del payload si no se
        // informan -- omitir businessUnit es lo que hace que la operación devuelva un
        // precio por cada almacén en vez de uno solo.
        if (businessUnit != null && !businessUnit.isBlank()) {
            value.put("businessUnit", padBusinessUnit(businessUnit));
        }
        if (currencyCode != null && !currencyCode.isBlank()) {
            value.put("currencyCode", currencyCode);
        }
        value.put("item", item);
        if (unitOfMeasureCode != null && !unitOfMeasureCode.isBlank()) {
            value.put("unitOfMeasureCode", unitOfMeasureCode);
        }
        return value;
    }

    private List<ItemListPriceEntry> parseItemListPrice(String rawJson) {
        JsonNode listaDeValores = parseListaDeValores(rawJson, OP_GET_ITEM_LIST_PRICE);
        JsonNode results = listaDeValores.path("showItemListPrice");

        List<ItemListPriceEntry> prices = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode entry : results) {
                JsonNode item = entry.path("item");
                prices.add(new ItemListPriceEntry(
                        entry.path("businessUnit").asText("").trim(),
                        item.path("itemId").asInt(),
                        item.path("itemCatalog").asText("").trim(),
                        item.path("itemDescription").asText("").trim(),
                        entry.path("priceList").decimalValue(),
                        entry.path("currencyCode").asText("").trim(),
                        entry.path("unitOfMeasureCode").asText("").trim(),
                        entry.path("dateEffective").asText(""),
                        entry.path("dateExpiration").asText("")));
            }
        }
        return prices;
    }

    private static String padBusinessUnit(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= BUSINESS_UNIT_FIELD_WIDTH) {
            return trimmed;
        }
        return " ".repeat(BUSINESS_UNIT_FIELD_WIDTH - trimmed.length()) + trimmed;
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

        String correlationId = correlationIdContext.getCorrelationId();

        return gatewayWebClient.post()
                .uri(gatewayBaseUrl + "/v1/operations/execute")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Token", "null")
                .header("TransactionId", gatewayTransactionId)
                .header("correlationUUID", correlationId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .block();
    }
}
