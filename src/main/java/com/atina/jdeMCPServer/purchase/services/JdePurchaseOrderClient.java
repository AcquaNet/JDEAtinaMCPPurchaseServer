package com.atina.jdeMCPServer.purchase.services;

import com.atina.jdeMCPServer.auth.JdeAuthService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private final WebClient gatewayWebClient;
    private final JdeAuthService authService;
    private final ObjectMapper objectMapper;
    private final PendingPurchaseOrderStore pendingOrderStore;
    private final String gatewayBaseUrl;
    private final String gatewayTransactionId;
    private final String defaultOrderTypeCode;
    private final String defaultBusinessUnitCode;
    private final String defaultStatusCodeNext;
    private final String defaultStatusApproval;
    private final String p43081Version;

    public JdePurchaseOrderClient(
            JdeAuthService authService,
            ObjectMapper objectMapper,
            PendingPurchaseOrderStore pendingOrderStore,
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
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.gatewayTransactionId = gatewayTransactionId;
        this.defaultOrderTypeCode = defaultOrderTypeCode;
        this.defaultBusinessUnitCode = defaultBusinessUnitCode;
        this.defaultStatusCodeNext = defaultStatusCodeNext;
        this.defaultStatusApproval = defaultStatusApproval;
        this.p43081Version = p43081Version;
    }

    // =========================================================================
    // Tool 1: Listar ordenes pendientes de aprobacion
    // =========================================================================
    public String getPendingPurchaseOrders(Integer limit, String orderTypeCode,
                                            String businessUnitCode, String statusCodeNext) {

        long approverAddressNumber = authService.getApproverAddressBookNumber();

        String effectiveOrderType = (orderTypeCode != null && !orderTypeCode.isBlank())
                ? orderTypeCode : defaultOrderTypeCode;
        String effectiveBusinessUnit = (businessUnitCode != null && !businessUnitCode.isBlank())
                ? businessUnitCode : defaultBusinessUnitCode;
        String effectiveStatusCodeNext = (statusCodeNext != null && !statusCodeNext.isBlank())
                ? statusCodeNext : defaultStatusCodeNext;

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

        JsonNode listaDeValores = executeGatewayOperation(OP_GET_PENDING_PURCHASE_ORDERS, value);
        JsonNode results = listaDeValores.path("purchaseOrdersForApproverResults");

        ArrayNode legacyShaped = objectMapper.createArrayNode();
        if (results.isArray()) {
            for (JsonNode item : results) {
                pendingOrderStore.put(item);
                legacyShaped.add(toLegacyShape(item));
            }
        }

        int effectiveLimit = (limit != null && limit > 0) ? limit : 10;
        ArrayNode limited = objectMapper.createArrayNode();
        for (int i = 0; i < legacyShaped.size() && i < effectiveLimit; i++) {
            limited.add(legacyShaped.get(i));
        }

        return writeValueAsString(limited, "la lista de ordenes pendientes");
    }

    private ObjectNode toLegacyShape(JsonNode item) {
        ObjectNode legacy = objectMapper.createObjectNode();
        legacy.put("documentCompanyKeyOrderNo", item.path("documentCompanyKeyOrderNo").asText(""));
        legacy.put("documentOrderTypeCode", item.path("documentOrderTypeCode").asText(""));
        legacy.put("documentOrderInvoiceNumber", item.path("documentOrderInvoiceNumber").asLong());
        legacy.put("documentSuffix", item.path("documentSuffix").asText(""));
        legacy.put("statusApproval", item.path("statusApproval").asText(""));
        legacy.put("approvalRoutingCodePurchaseOrder", item.path("approvalRoutingCodePurchaseOrder").asText(""));
        legacy.put("entityNameOriginator", item.path("entityNameOriginator").asText(""));
        legacy.put("entityNameSupplier", item.path("entityNameSupplier").asText(""));
        legacy.put("entityNameShipTo", item.path("entityNameShipTo").asText(""));
        legacy.put("currencyMode", item.path("currencyMode").asText(""));
        legacy.put("amountGross", item.path("amountGross").decimalValue());
        legacy.put("currencyCodeBase", item.path("currencyCodeBase").asText(""));
        legacy.put("amountForeign", item.path("amountForeign").decimalValue());
        legacy.put("currencyCodeForeign", item.path("currencyCodeForeign").asText(""));
        legacy.put("businessUnitCode", item.path("businessUnitCode").asText("").trim());
        legacy.put("dateRequested", truncateToDate(item.path("dateRequested").asText(null)));
        legacy.put("dateTransaction", truncateToDate(item.path("dateTransaction").asText(null)));

        boolean domestic = "D".equals(item.path("currencyMode").asText(""));
        ObjectNode calc = legacy.putObject("calculateValues");
        if (domestic) {
            calc.put("amountToApprove", item.path("amountGross").decimalValue());
            calc.put("currencyToApprove", item.path("currencyCodeBase").asText(""));
        } else {
            calc.put("amountToApprove", item.path("amountForeign").decimalValue());
            calc.put("currencyToApprove", item.path("currencyCodeForeign").asText(""));
        }
        calc.put("daysOld", computeDaysOld(item.path("dateTransaction").asText(null)));

        return legacy;
    }

    // =========================================================================
    // Tool 2: Detalle de una orden (header + detail combinando dos operaciones)
    // =========================================================================
    public String getPurchaseOrderDetail(String documentOrderTypeCode,
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

        ObjectNode combined = objectMapper.createObjectNode();
        combined.set("header", showPurchaseOrder);
        combined.set("detail", detailResults);

        return writeValueAsString(combined, "el detalle de la orden de compra");
    }

    private static void notifyProgress(Consumer<String> onProgress, String message) {
        if (onProgress != null) {
            onProgress.accept(message);
        }
    }

    // =========================================================================
    // Tool 3: Aprobar / rechazar una orden
    // =========================================================================
    public String processPurchaseOrder(String action,
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

        JsonNode result = executeGatewayOperation(OP_PROCESS_APPROVE_REJECT, value);

        pendingOrderStore.remove(key);

        return writeValueAsString(result, "la respuesta de aprobación/rechazo");
    }

    // =========================================================================
    // Gateway de Atina - helper comun
    // =========================================================================
    private JsonNode executeGatewayOperation(String operacionKey, Map<String, Object> value) {

        String token = authService.getOrCreateToken();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operacionKey", operacionKey);
        body.put("listaDeValores", List.of(value));
        body.put("connectorName", "WS");

        ResponseEntity<String> response = gatewayWebClient.post()
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

        authService.updateTokenFromResponse(response.getHeaders());

        try {
            return objectMapper.readTree(response.getBody()).path("listaDeValores");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Respuesta inválida del Gateway de Atina para la operación " + operacionKey, e);
        }
    }

    private String writeValueAsString(Object value, String description) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Error serializando " + description, e);
        }
    }

    private static String padBusinessUnit(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= BUSINESS_UNIT_FIELD_WIDTH) {
            return trimmed;
        }
        return " ".repeat(BUSINESS_UNIT_FIELD_WIDTH - trimmed.length()) + trimmed;
    }

    private static String truncateToDate(String isoDateTime) {
        if (isoDateTime == null) {
            return null;
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
