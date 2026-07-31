package com.atina.jdeMCPServer.purchase.services;

import com.atina.jdeMCPServer.auth.JdeAuthService;
import com.atina.jdeMCPServer.gateway.RequestCoalescer;
import com.atina.jdeMCPServer.mcp.CorrelationIdContext;
import com.atina.jdeMCPServer.purchase.model.PendingPurchaseOrderSummary;
import com.atina.jdeMCPServer.purchase.model.PurchaseOrderDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class JdePurchaseOrderClient {

    private static final Logger log = LoggerFactory.getLogger(JdePurchaseOrderClient.class);

    // BSSV operations expuestas por el Gateway de Atina (endpoint /v1/operations/execute).
    private static final String OP_GET_PENDING_PURCHASE_ORDERS =
            "oracle.e1.bssv.JP430000.ProcurementManager.getPurchaseOrdersForApprover";
    private static final String OP_GET_PURCHASE_ORDER_V2 =
            "oracle.e1.bssv.JP430000.ProcurementManager.getPurchaseOrderV2";
    private static final String OP_GET_PURCHASE_ORDER_DETAIL =
            "oracle.e1.bssv.JP430000.ProcurementManager.getPurchaseOrderDetailForApprover";
    private static final String OP_PROCESS_APPROVE_REJECT =
            "oracle.e1.bssv.JP430000.ProcurementManager.processPurchaseOrderApproveReject";

    // Valor fijo confirmado para purchaseOrderKey.documentCompany en getPurchaseOrderV2:
    // no es el documentCompanyKeyOrderNo real de la orden (siempre "000").
    private static final String FIXED_DOCUMENT_COMPANY = "000";

    private static final int BUSINESS_UNIT_FIELD_WIDTH = 12;

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP_TYPE_REF = new TypeReference<>() {
    };

    private final WebClient gatewayWebClient;
    private final JdeAuthService authService;
    private final ObjectMapper objectMapper;
    private final PendingPurchaseOrderStore pendingOrderStore;
    private final RequestCoalescer requestCoalescer;
    private final String gatewayBaseUrl;
    private final String gatewayTransactionId;
    private final String defaultOrderTypeCode;
    private final String defaultBusinessUnitCode;
    private final String defaultStatusCodeNext;
    private final String defaultStatusApproval;
    private final String p43081Version;
    private final CorrelationIdContext correlationIdContext;

    public JdePurchaseOrderClient(
            JdeAuthService authService,
            ObjectMapper objectMapper,
            PendingPurchaseOrderStore pendingOrderStore,
            RequestCoalescer requestCoalescer,
            @Value("${jde.atina.gateway.base-url}") String gatewayBaseUrl,
            @Value("${jde.atina.gateway.timeout-minutes:10}") int gatewayTimeoutMinutes,
            @Value("${jde.atina.gateway.transaction-id:0}") String gatewayTransactionId,
            @Value("${jde.purchase.default-order-type-code}") String defaultOrderTypeCode,
            @Value("${jde.purchase.default-business-unit-code}") String defaultBusinessUnitCode,
            @Value("${jde.purchase.default-status-code-next}") String defaultStatusCodeNext,
            @Value("${jde.purchase.default-status-approval}") String defaultStatusApproval,
            @Value("${jde.purchase.p43081-version}") String p43081Version) {

        this.gatewayWebClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMinutes(gatewayTimeoutMinutes))
        )).build();
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.pendingOrderStore = pendingOrderStore;
        this.requestCoalescer = requestCoalescer;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.gatewayTransactionId = gatewayTransactionId;
        this.defaultOrderTypeCode = defaultOrderTypeCode;
        this.defaultBusinessUnitCode = defaultBusinessUnitCode;
        this.defaultStatusCodeNext = defaultStatusCodeNext;
        this.defaultStatusApproval = defaultStatusApproval;
        this.p43081Version = p43081Version;
        this.correlationIdContext = correlationIdContext;
    }

    // =========================================================================
    // Tool 1: Listar ordenes pendientes de aprobacion
    // =========================================================================
    public List<PendingPurchaseOrderSummary> getPendingPurchaseOrders(String orderTypeCode,
                                                                        String businessUnitCode,
                                                                        String statusCodeNext) {
        long approverAddressNumber = authService.getApproverAddressBookNumber();
        return fetchAllPendingOrders(orderTypeCode, businessUnitCode, statusCodeNext,
                approverAddressNumber, this::executeGatewayOperation);
    }

    /**
     * Resuelve de una sola vez el token de sesión JDE y el address book number
     * del aprobador logueado -- pensado para ejecuciones que necesitan pasar
     * ese contexto como dato plano fuera del ciclo de vida del
     * HttpServletRequest original (ver {@link #executeGatewayOperationWithToken}).
     */
    public record ApproverContext(String token, long approverAddressNumber) {
    }

    public ApproverContext resolveApproverContext() {
        String token = authService.getOrCreateToken();
        return new ApproverContext(token, authService.extractApproverAddressBookNumber(token));
    }

    /**
     * Igual que {@link #getPendingPurchaseOrders} pero con un {@link ApproverContext}
     * ya resuelto -- usado por la rama asíncrona (LongRunningTaskRegistry), que
     * cachea el resultado completo (sin recorte por `limit`) y aplica el recorte
     * recién al servir la respuesta (ver JdePurchaseApprovalTool).
     */
    public List<PendingPurchaseOrderSummary> fetchAllPendingOrdersWithToken(String orderTypeCode, String businessUnitCode,
                                                                             String statusCodeNext, ApproverContext ctx) {
        return fetchAllPendingOrders(orderTypeCode, businessUnitCode, statusCodeNext,
                ctx.approverAddressNumber(),
                (op, val) -> executeGatewayOperationWithToken(op, val, ctx.token()));
    }

    /** Valores de filtro ya defaulteados (ver {@link #resolveEffectiveFilters}). */
    public record EffectiveFilters(String orderType, String businessUnit, String statusCodeNext) {
    }

    /**
     * Aplica los defaults configurados (jde.purchase.default-*) a los filtros
     * opcionales del tool -- expuesto como método público para que un caller
     * externo (ej. la key de deduplicación de un mecanismo asíncrono) calcule
     * los MISMOS valores "efectivos" que usa la llamada real al Gateway, sin
     * duplicar esta lógica en dos lugares.
     */
    public EffectiveFilters resolveEffectiveFilters(String orderTypeCode, String businessUnitCode,
                                                     String statusCodeNext) {
        String effectiveOrderType = (orderTypeCode != null && !orderTypeCode.isBlank())
                ? orderTypeCode : defaultOrderTypeCode;
        String effectiveBusinessUnit = (businessUnitCode != null && !businessUnitCode.isBlank())
                ? businessUnitCode : defaultBusinessUnitCode;
        String effectiveStatusCodeNext = (statusCodeNext != null && !statusCodeNext.isBlank())
                ? statusCodeNext : defaultStatusCodeNext;
        return new EffectiveFilters(effectiveOrderType, effectiveBusinessUnit, effectiveStatusCodeNext);
    }

    private List<PendingPurchaseOrderSummary> fetchAllPendingOrders(
            String orderTypeCode, String businessUnitCode, String statusCodeNext,
            long approverAddressNumber,
            java.util.function.BiFunction<String, Map<String, Object>, JsonNode> operationExecutor) {

        EffectiveFilters filters = resolveEffectiveFilters(orderTypeCode, businessUnitCode, statusCodeNext);
        String effectiveOrderType = filters.orderType();
        String effectiveBusinessUnit = filters.businessUnit();
        String effectiveStatusCodeNext = filters.statusCodeNext();

        log.info("Requesting pending purchase orders for approver {} (orderType={}, businessUnit={}, statusCodeNext={})",
                approverAddressNumber, effectiveOrderType, effectiveBusinessUnit, effectiveStatusCodeNext);

        Map<String, Object> approver = new LinkedHashMap<>();
        approver.put("entityId", approverAddressNumber);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("approver", approver);
        value.put("orderTypeCode", effectiveOrderType);
        value.put("businessUnitCode", padBusinessUnit(effectiveBusinessUnit));
        value.put("statusCodeNext", effectiveStatusCodeNext);
        value.put("statusApproval", defaultStatusApproval);

        JsonNode listaDeValores = operationExecutor.apply(OP_GET_PENDING_PURCHASE_ORDERS, value);
        JsonNode results = listaDeValores.path("purchaseOrdersForApproverResults");

        List<PendingPurchaseOrderSummary> summaries = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode item : results) {
                pendingOrderStore.put(item);
                summaries.add(toSummary(item));
            }
        }
        return summaries;
    }

    private PendingPurchaseOrderSummary toSummary(JsonNode item) {
        boolean domestic = "D".equals(item.path("currencyMode").asText(""));
        java.math.BigDecimal amountToApprove = domestic
                ? item.path("amountGross").decimalValue()
                : item.path("amountForeign").decimalValue();
        String currencyToApprove = domestic
                ? item.path("currencyCodeBase").asText("")
                : item.path("currencyCodeForeign").asText("");

        return new PendingPurchaseOrderSummary(
                item.path("documentOrderTypeCode").asText(""),
                item.path("documentOrderInvoiceNumber").asLong(),
                item.path("documentCompanyKeyOrderNo").asText(""),
                item.path("documentSuffix").asText(""),
                item.path("entityNameSupplier").asText("").trim(),
                item.path("entityNameShipTo").asText("").trim(),
                amountToApprove,
                currencyToApprove,
                computeDaysOld(item.path("dateTransaction").asText(null)),
                truncateToDate(item.path("dateRequested").asText(null)),
                truncateToDate(item.path("dateTransaction").asText(null)));
    }

    // =========================================================================
    // Tool 2: Detalle de una orden (header + detail combinando dos operaciones)
    // =========================================================================
    public PurchaseOrderDetail getPurchaseOrderDetail(String documentOrderTypeCode,
                                                       Integer documentOrderInvoiceNumber,
                                                       String documentCompanyKeyOrderNo,
                                                       String documentSuffix,
                                                       Consumer<String> onProgress) {

        Map<String, Object> purchaseOrderKey = new LinkedHashMap<>();
        purchaseOrderKey.put("documentTypeCode", documentOrderTypeCode);
        purchaseOrderKey.put("documentNumber", documentOrderInvoiceNumber);
        purchaseOrderKey.put("documentCompany", FIXED_DOCUMENT_COMPANY);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("purchaseOrderKey", purchaseOrderKey);

        Map<String, Object> headerValue = new LinkedHashMap<>();
        headerValue.put("header", header);

        notifyProgress(onProgress, "Consultando cabecera de la orden en JDE...");
        JsonNode headerLista = executeGatewayOperation(OP_GET_PURCHASE_ORDER_V2, headerValue);
        JsonNode showPurchaseOrder = headerLista.path("showPurchaseOrder");

        Map<String, Object> detailValue = new LinkedHashMap<>();
        detailValue.put("companyKeyOrderNo", documentCompanyKeyOrderNo);
        detailValue.put("documentOrderTypeCode", documentOrderTypeCode);
        detailValue.put("documentOrderInvoiceNumber", documentOrderInvoiceNumber);
        detailValue.put("documentSuffix", documentSuffix);
        detailValue.put("statusCodeNext", defaultStatusCodeNext);

        notifyProgress(onProgress, "Consultando el detalle de líneas en JDE...");
        JsonNode detailLista = executeGatewayOperation(OP_GET_PURCHASE_ORDER_DETAIL, detailValue);
        JsonNode detailResults = detailLista.path("purchaseOrderDetailForApproverResults");

        // isMissingNode()/isNull() se chequean a mano -- convertValue sobre esos nodos
        // puede devolver null, y esto va directo a un campo no-nullable de un record
        // de salida MCP (ver PurchaseOrderDetail).
        Map<String, Object> headerFields = (showPurchaseOrder.isMissingNode() || showPurchaseOrder.isNull())
                ? Map.of()
                : objectMapper.convertValue(showPurchaseOrder, MAP_TYPE_REF);
        List<Map<String, Object>> lines = detailResults.isArray()
                ? objectMapper.convertValue(detailResults, LIST_OF_MAP_TYPE_REF)
                : List.of();

        return new PurchaseOrderDetail(headerFields, lines);
    }

    private static void notifyProgress(Consumer<String> onProgress, String message) {
        if (onProgress != null) {
            onProgress.accept(message);
        }
    }

    // =========================================================================
    // Tool 3: Aprobar / rechazar una orden
    // =========================================================================
    public void processPurchaseOrder(String action,
                                      String documentOrderTypeCode,
                                      Integer documentOrderInvoiceNumber,
                                      String documentCompanyKeyOrderNo,
                                      String documentSuffix,
                                      String remark) {

        String key = PendingPurchaseOrderStore.buildKey(
                documentCompanyKeyOrderNo,
                documentOrderTypeCode,
                documentOrderInvoiceNumber != null ? String.valueOf(documentOrderInvoiceNumber) : "",
                documentSuffix);

        JsonNode cached = pendingOrderStore.get(key).orElseThrow(() -> new IllegalStateException(
                "No se encontró información en caché para la orden de compra "
                        + documentOrderTypeCode + " " + documentOrderInvoiceNumber + " / "
                        + documentCompanyKeyOrderNo + "-" + documentSuffix
                        + ". Volvé a llamar a jde_list_pending_purchase_orders antes de aprobar o rechazar."));

        long approverAddressNumber = authService.getApproverAddressBookNumber();

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("p43081Version", p43081Version);
        value.put("businessUnit", cached.path("businessUnitCode").asText("").trim());
        value.put("companyKeyOrderNumber", documentCompanyKeyOrderNo);
        value.put("documentType", documentOrderTypeCode);
        value.put("documentNumber", documentOrderInvoiceNumber);
        value.put("orderSuffix", documentSuffix);
        value.put("remark", remark);
        value.put("statusApproval", cached.path("statusApproval").asText(""));
        value.put("amountToApprove", cached.path("amountGross").decimalValue());
        value.put("approveReject", action);
        value.put("approverAddressNumber", approverAddressNumber);
        value.put("detail", List.of());
        value.put("approvalRouteCode", cached.path("approvalRoutingCodePurchaseOrder").asText(""));

        executeGatewayOperation(OP_PROCESS_APPROVE_REJECT, value);

        pendingOrderStore.remove(key);
    }

    // =========================================================================
    // Gateway de Atina - helper comun
    // =========================================================================
    // Coalescea llamadas identicas concurrentes (mismo operacionKey + mismo body):
    // si un cliente MCP cancela por timeout y reintenta la misma tool call mientras
    // la anterior sigue esperando a JDE, el reintento espera el resultado de la
    // llamada en curso en vez de disparar una nueva contra el Gateway de Atina.
    private JsonNode executeGatewayOperation(String operacionKey, Map<String, Object> value) {
        return requestCoalescer.execute(
                coalesceKey(operacionKey, value),
                () -> doExecuteGatewayOperation(operacionKey, value));
    }

    private String coalesceKey(String operacionKey, Map<String, Object> value) {
        try {
            return operacionKey + "|" + objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return operacionKey + "|" + value;
        }
    }

    private JsonNode doExecuteGatewayOperation(String operacionKey, Map<String, Object> value) {
        String token = authService.getOrCreateToken();
        ResponseEntity<String> response = postToGateway(operacionKey, value, token);
        authService.updateTokenFromResponse(response.getHeaders());
        return parseListaDeValores(response, operacionKey);
    }

    /**
     * Variante de {@link #executeGatewayOperation(String, Map)} con un token ya
     * resuelto (en vez de resolverlo internamente vía authService). Pensada para
     * ejecuciones que corren fuera del ciclo de vida del HttpServletRequest
     * original (ver {@link #resolveApproverContext()}), donde authService no
     * tiene RequestContextHolder disponible.
     *
     * NO llama authService.updateTokenFromResponse(...) -- ese refresh de cache
     * necesita metadata del request original que acá no está disponible. Hoy es
     * un no-op de todas formas bajo jde.atina.session-source=claim (el default
     * activo en los tres perfiles); solo importaría si algún ambiente cambiara a
     * Identity Bridge o login manual, en cuyo caso el peor caso es una sesión
     * que se refresca un poco más tarde de lo ideal (fuerza un re-login
     * eventual), no un problema de seguridad.
     */
    private JsonNode executeGatewayOperationWithToken(String operacionKey, Map<String, Object> value, String token) {
        return requestCoalescer.execute(
                coalesceKey(operacionKey, value),
                () -> doExecuteGatewayOperationWithToken(operacionKey, value, token));
    }

    private JsonNode doExecuteGatewayOperationWithToken(String operacionKey, Map<String, Object> value, String token) {
        ResponseEntity<String> response = postToGateway(operacionKey, value, token);
        return parseListaDeValores(response, operacionKey);
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

    private JsonNode parseListaDeValores(ResponseEntity<String> response, String operacionKey) {
        try {
            return objectMapper.readTree(response.getBody()).path("listaDeValores");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Respuesta inválida del Gateway de Atina para la operación " + operacionKey, e);
        }
    }

    private static String padBusinessUnit(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= BUSINESS_UNIT_FIELD_WIDTH) {
            return trimmed;
        }
        return " ".repeat(BUSINESS_UNIT_FIELD_WIDTH - trimmed.length()) + trimmed;
    }

    // Nunca null (aunque isoDateTime lo sea) -- va directo a un campo String de un
    // record de salida MCP, y el SDK rechaza null en campos no declarados nullable.
    private static String truncateToDate(String isoDateTime) {
        if (isoDateTime == null) {
            return "";
        }
        int idx = isoDateTime.indexOf('T');
        return idx > 0 ? isoDateTime.substring(0, idx) : isoDateTime;
    }

    private static long computeDaysOld(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank()) {
            return 0;
        }
        LocalDate transactionDate = OffsetDateTime.parse(isoDateTime).toLocalDate();
        return ChronoUnit.DAYS.between(transactionDate, LocalDate.now());
    }
}
