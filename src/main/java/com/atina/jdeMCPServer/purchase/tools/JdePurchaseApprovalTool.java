package com.atina.jdeMCPServer.purchase.tools;

import com.atina.jdeMCPServer.mcp.McpProgressNotifications;
import com.atina.jdeMCPServer.mcp.CorrelationIdContext;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTask;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTaskRegistry;
import com.atina.jdeMCPServer.purchase.model.PendingPurchaseOrderSummary;
import com.atina.jdeMCPServer.purchase.model.PendingPurchaseOrdersResult;
import com.atina.jdeMCPServer.purchase.model.PurchaseOrderActionResult;
import com.atina.jdeMCPServer.purchase.model.PurchaseOrderDetail;
import com.atina.jdeMCPServer.purchase.model.PurchaseOrderDetailResult;
import com.atina.jdeMCPServer.purchase.model.ToolStatus;
import com.atina.jdeMCPServer.purchase.services.JdePurchaseOrderClient;
import com.atina.jdeMCPServer.salesorder.services.JdeSalesOrderClient;
import com.atina.jdeMCPServer.security.RealmRoleGuard;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class JdePurchaseApprovalTool {

    private static final Logger log = LoggerFactory.getLogger(JdePurchaseApprovalTool.class);

    private final JdePurchaseOrderClient jdeClient;
    private final RealmRoleGuard roleGuard;
    private final String approverRole;
    private final McpProgressNotifications progressNotifications;
    private final LongRunningTaskRegistry taskRegistry;
    private final boolean asyncPendingOrdersEnabled;
    private final long initialWaitSeconds;
    private final long gatewayTimeoutMinutes;
    private final long defaultPollIntervalMs;
    private final CorrelationIdContext correlationIdContext;


    public JdePurchaseApprovalTool(JdePurchaseOrderClient jdeClient,
                                   RealmRoleGuard roleGuard,
                                   @Value("${jde.mcp.security.approver-role}") String approverRole,
                                   McpProgressNotifications progressNotifications,
                                   LongRunningTaskRegistry taskRegistry,
                                   @Value("${jde.purchase.pending-orders.async.enabled:true}") boolean asyncPendingOrdersEnabled,
                                   @Value("${jde.purchase.pending-orders.async.initial-wait-seconds:8}") long initialWaitSeconds,
                                   @Value("${jde.atina.gateway.timeout-minutes:10}") long gatewayTimeoutMinutes,
                                   @Value("${jde.mcp.tasks.default-poll-interval-ms:5000}") long defaultPollIntervalMs,
            CorrelationIdContext correlationIdContext) {
        this.jdeClient = jdeClient;
        this.roleGuard = roleGuard;
        this.approverRole = approverRole;
        this.progressNotifications = progressNotifications;
        this.taskRegistry = taskRegistry;
        this.correlationIdContext = correlationIdContext;
        this.asyncPendingOrdersEnabled = asyncPendingOrdersEnabled;
        this.initialWaitSeconds = initialWaitSeconds;
        this.gatewayTimeoutMinutes = gatewayTimeoutMinutes;
        this.defaultPollIntervalMs = defaultPollIntervalMs;
    }

    // =========================
    // Tool 1: Listar pendientes
    // =========================
    @McpTool(
            name = "jde_list_pending_purchase_orders",
            description = """
        List JDE purchase orders that are currently pending approval for the logged-in approver.

        Use this tool whenever the user wants to:
        - See which purchase orders are waiting for their approval.
        - Review basic information (supplier, amount, currency, date) before deciding what to approve or reject.
        - Choose a specific purchase order to act on in a follow-up step.

        INPUT:
        - limit: maximum number of orders to return (for example 5 or 10). If not provided or
          invalid, defaults to 10. Use smaller limits to keep the list manageable.
        - orderTypeCode / businessUnitCode / statusCodeNext: optional JDE filters; a configured
          default is used for any that are omitted.

        OUTPUT (structured JSON, see outputSchema):
        - status "IN_PROGRESS": the query is still running against a busy JDE environment (this can
          take several minutes). This is NOT an error -- call jde_list_pending_purchase_orders again
          with the exact same parameters after pollAfterSeconds to get the actual list. Never report
          this to the user as a failure.
        - status "OK": orders[] has the matches (capped at limit), each with the four identifiers
          needed to chain into jde_get_purchase_order_detail / jde_approve_purchase_order /
          jde_reject_purchase_order (documentOrderTypeCode, documentOrderInvoiceNumber,
          documentCompanyKeyOrderNo, documentSuffix), plus supplierName, shipToName, amountToApprove,
          currencyToApprove, daysOld, dateRequested, dateTransaction.
        - status "FAILED" / "CANCELLED": message explains what happened; retrying is safe.

        IMPORTANT FOR THE ASSISTANT:
        - Always call this tool BEFORE asking the user to approve or reject a specific purchase order.
        - Do NOT invent or assume purchase order identifiers. Use exactly the values returned by this tool.
        - Present orders[] as a Markdown table (PO ID = documentOrderTypeCode + "-" + documentOrderInvoiceNumber,
          Company, Supplier, Ship To, Amount, Currency, Days Old, Requested, Transaction Date), sorted by
          Days Old descending (or Requested Date, oldest first, if daysOld isn't meaningful).
        - If orders[] has exactly `limit` entries, more may exist beyond the limit -- consider asking
          the user to raise the limit.
        - After the table, provide a short 2-4 sentence summary highlighting the oldest purchase orders,
          highest amounts, and any clear priority items for review.
        """,
            generateOutputSchema = true
    )
    public PendingPurchaseOrdersResult getPendingPurchaseOrders(
            @McpToolParam(
                    description = "Maximum number of pending purchase orders to return (for example 5 or 10). "
                            + "If not provided or invalid, defaults to 10.",
                    required = false
            )
            Integer limit,
            @McpToolParam(
                    description = "JDE order type code, e.g. 'OP'. Optional: if not provided, a configured default is used.",
                    required = false
            )
            String orderTypeCode,
            @McpToolParam(
                    description = "JDE business unit code (MCU), e.g. '30'. Optional: if not provided, a configured default is used.",
                    required = false
            )
            String businessUnitCode,
            @McpToolParam(
                    description = "JDE next status code of the approval workflow, e.g. '230'. Optional: if not provided, a configured default is used.",
                    required = false
            )
            String statusCodeNext,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        String correlationId = correlationIdContext.extractAndSet(meta);
        log.info("Tool 'jde_list_pending_purchase_orders' called with correlation ID: {}", correlationId);

        if (!asyncPendingOrdersEnabled) {
            progressNotifications.send(exchange, meta, 0, null,
                    "Consultando órdenes pendientes en JDE, puede tardar unos segundos...");
            List<PendingPurchaseOrderSummary> orders =
                    jdeClient.getPendingPurchaseOrders(orderTypeCode, businessUnitCode, statusCodeNext);
            return new PendingPurchaseOrdersResult(ToolStatus.OK, "", 0,
                    JdeSalesOrderClient.applyLimit(orders, limit));
        }

        var ctx = jdeClient.resolveApproverContext();
        var filters = jdeClient.resolveEffectiveFilters(orderTypeCode, businessUnitCode, statusCodeNext);
        String key = "pending-orders|" + ctx.approverAddressNumber()
                + "|" + filters.orderType() + "|" + filters.businessUnit() + "|" + filters.statusCodeNext();

        LongRunningTask task = taskRegistry.getOrStart(
                key,
                Duration.ofMinutes(gatewayTimeoutMinutes + 1),
                defaultPollIntervalMs,
                Duration.ofSeconds(initialWaitSeconds),
                correlationIdContext.wrapForBackgroundThread(correlationId,
                        () -> jdeClient.fetchAllPendingOrdersWithToken(orderTypeCode, businessUnitCode, statusCodeNext, ctx)));

        return switch (task.status()) {
            case WORKING, INPUT_REQUIRED -> new PendingPurchaseOrdersResult(
                    ToolStatus.IN_PROGRESS,
                    "The query for pending purchase orders is still in progress (this can take several "
                            + "minutes against a busy JDE environment). Call jde_list_pending_purchase_orders "
                            + "again with the exact same parameters after pollAfterSeconds to get the "
                            + "results -- this is not an error.",
                    (int) ((task.pollIntervalMs() != null ? task.pollIntervalMs() : defaultPollIntervalMs) / 1000),
                    List.of());
            case COMPLETED -> {
                @SuppressWarnings("unchecked")
                List<PendingPurchaseOrderSummary> orders = (List<PendingPurchaseOrderSummary>) task.result();
                yield new PendingPurchaseOrdersResult(ToolStatus.OK, "", 0,
                        JdeSalesOrderClient.applyLimit(orders, limit));
            }
            case FAILED -> {
                log.error("Error listing pending purchase orders: {}", task.error());
                yield new PendingPurchaseOrdersResult(ToolStatus.FAILED,
                        "An error occurred while querying pending purchase orders in JDE. "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, List.of());
            }
            case CANCELLED -> new PendingPurchaseOrdersResult(ToolStatus.CANCELLED,
                    "The query was cancelled. Call jde_list_pending_purchase_orders again to retry.",
                    0, List.of());
        };
    }

    // =========================
    // Tool 2: Ver detalle de una OC
    // =========================
    @McpTool(
            name = "jde_get_purchase_order_detail",
            description = """
            Retrieve detailed information for a specific JDE purchase order that is pending approval.

            INPUT:
            - The caller must provide the four required JDE identifiers, exactly as returned by
              jde_list_pending_purchase_orders: documentOrderTypeCode, documentOrderInvoiceNumber,
              documentCompanyKeyOrderNo, documentSuffix.
            - Never guess or invent values. If any identifier is missing or unclear, ask the user to
              provide or confirm it (the user has normally already seen the list of pending orders).

            OUTPUT (structured JSON, see outputSchema):
            - status "OK": header and lines are populated. header is a JSON object with the purchase
              order's header fields (type, number, company, suffix, supplier, ship-to, dates, amounts,
              currency, etc. -- exact field names depend on the JDE response and are not individually
              declared in the schema, present whichever of these you find). lines is a JSON array,
              one entry per order line (item, description, quantity, price, amount, currency, etc.).
            - status "INVALID_REQUEST": a required identifier was missing.
            - status "FAILED": message explains the error; retrying is safe.

            IMPORTANT FOR THE ASSISTANT:
            - Present header as a 2-column key/value Markdown table (PO Type, PO Number, Company,
              Suffix, Supplier Name, Ship-To Name, Requested Date, Transaction Date, Total Amount,
              Currency, Days Old -- whichever are present in header).
            - Present lines as a Markdown table, one row per line (Line, Item Number, Description,
              Quantity, Unit Price, Extended Amount, Currency, and any other relevant field present).
            - After the tables, add a short 2-4 sentence summary highlighting large amounts, very old
              orders, unusual line items, or potential risks, then ask whether the user wants to
              approve/reject this order or review another one in detail.
            """,
            generateOutputSchema = true
    )
    public PurchaseOrderDetailResult getPurchaseOrderDetail(
            @McpToolParam(
                    description = "JDE documentOrderTypeCode, for example 'OP'."
            )
            String documentOrderTypeCode,
            @McpToolParam(
                    description = "JDE documentOrderInvoiceNumber, for example 5067."
            )
            Integer documentOrderInvoiceNumber,
            @McpToolParam(
                    description = "JDE documentCompanyKeyOrderNo, for example '00001'."
            )
            String documentCompanyKeyOrderNo,
            @McpToolParam(
                    description = "JDE documentSuffix, for example '000'."
            )
            String documentSuffix,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        log.info("Tool 'jde_get_purchase_order_detail' called with correlation ID: {}", correlationIdContext.extractAndSet(meta));

        if (documentOrderTypeCode == null || documentOrderTypeCode.isBlank()
                || documentOrderInvoiceNumber == null
                || documentCompanyKeyOrderNo == null || documentCompanyKeyOrderNo.isBlank()
                || documentSuffix == null || documentSuffix.isBlank()) {
            return new PurchaseOrderDetailResult(ToolStatus.INVALID_REQUEST,
                    "Please provide all four purchase order identifiers (documentOrderTypeCode, "
                            + "documentOrderInvoiceNumber, documentCompanyKeyOrderNo, documentSuffix), "
                            + "exactly as returned by jde_list_pending_purchase_orders.",
                    safe(documentOrderTypeCode), documentOrderInvoiceNumber != null ? documentOrderInvoiceNumber.longValue() : 0L,
                    safe(documentCompanyKeyOrderNo), safe(documentSuffix), Map.of(), List.of());
        }

        log.info(
                "Requesting detail for PO type={} number={} company={} suffix={}",
                documentOrderTypeCode, documentOrderInvoiceNumber,
                documentCompanyKeyOrderNo, documentSuffix
        );

        try {
            // Llamada real al microservicio a través del cliente. El detalle combina dos
            // operaciones BSSV secuenciales (header + line items); se reporta progreso
            // entre ambas para que un cliente MCP que siga el progressToken no timeoutee
            // a mitad de camino.
            PurchaseOrderDetail detail = jdeClient.getPurchaseOrderDetail(
                    documentOrderTypeCode,
                    documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo,
                    documentSuffix,
                    progressMessage -> progressNotifications.send(exchange, meta, 0, null, progressMessage)
            );

            return new PurchaseOrderDetailResult(ToolStatus.OK, "",
                    documentOrderTypeCode, documentOrderInvoiceNumber.longValue(),
                    documentCompanyKeyOrderNo, documentSuffix,
                    detail.header(), detail.lines());

        } catch (Exception e) {
            log.error(
                    "Error retrieving detail for PO type={} number={} company={} suffix={}",
                    documentOrderTypeCode, documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo, documentSuffix, e
            );

            return new PurchaseOrderDetailResult(ToolStatus.FAILED,
                    "An error occurred while trying to retrieve the details of purchase order "
                            + documentOrderTypeCode + " " + documentOrderInvoiceNumber + " / "
                            + documentCompanyKeyOrderNo + "-" + documentSuffix + ". "
                            + "Technical details have been logged in the MCP server. "
                            + "Ask the user to try again later or contact support.",
                    documentOrderTypeCode, documentOrderInvoiceNumber.longValue(),
                    documentCompanyKeyOrderNo, documentSuffix, Map.of(), List.of());
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    // =========================
    // Tool: Aprobar OC
    // =========================
    @McpTool(
            name = "jde_approve_purchase_order",
            description = """
            Approve a specific JDE purchase order.

            Use this tool only after:
            - Listing the pending purchase orders, and
            - (optionally) showing the purchase order detail to the user.

            OUTPUT (structured JSON, see outputSchema):
            - status "OK": the order was approved. action is "A".
            - status "UNAUTHORIZED": the caller does not have the required Keycloak role.
            - status "FAILED": message explains the error (e.g. the order isn't cached anymore --
              call jde_list_pending_purchase_orders again first); retrying after fixing the cause is safe.

            IMPORTANT FOR THE ASSISTANT:
            - You MUST provide all four JDE identifiers exactly as returned by the pending-orders or detail tool:
              documentOrderTypeCode, documentOrderInvoiceNumber, documentCompanyKeyOrderNo, documentSuffix.
            - Do NOT guess or invent values.
            - Ask the user for a short remark explaining the approval reason if appropriate.
            - The remark will be truncated to 30 characters as required by the backend API.
            """,
            generateOutputSchema = true
    )
    public PurchaseOrderActionResult approvePurchaseOrder(
            @McpToolParam(description = "JDE documentOrderTypeCode, e.g. 'OP'.")
            String documentOrderTypeCode,
            @McpToolParam(description = "JDE documentOrderInvoiceNumber, e.g. 5067.")
            Integer documentOrderInvoiceNumber,
            @McpToolParam(description = "JDE documentCompanyKeyOrderNo, e.g. '00001'.")
            String documentCompanyKeyOrderNo,
            @McpToolParam(description = "JDE documentSuffix, e.g. '000'.")
            String documentSuffix,
            @McpToolParam(description = "Short approval remark (will be truncated to 30 characters).")
            String remark,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        log.info("Tool 'jde_approve_purchase_order' called with correlation ID: {}", correlationIdContext.extractAndSet(meta));

        return processPurchaseOrderInternal(
                "A",
                documentOrderTypeCode,
                documentOrderInvoiceNumber,
                documentCompanyKeyOrderNo,
                documentSuffix,
                remark,
                meta,
                exchange
        );
    }

    // =========================
    // Tool: Rechazar OC
    // =========================
    @McpTool(
            name = "jde_reject_purchase_order",
            description = """
            Reject a specific JDE purchase order.

            Use this tool only after:
            - Listing the pending purchase orders, and
            - (optionally) showing the purchase order detail to the user.

            OUTPUT (structured JSON, see outputSchema):
            - status "OK": the order was rejected. action is "R".
            - status "UNAUTHORIZED": the caller does not have the required Keycloak role.
            - status "FAILED": message explains the error (e.g. the order isn't cached anymore --
              call jde_list_pending_purchase_orders again first); retrying after fixing the cause is safe.

            IMPORTANT FOR THE ASSISTANT:
            - You MUST provide all four JDE identifiers exactly as returned by the pending-orders or detail tool:
              documentOrderTypeCode, documentOrderInvoiceNumber, documentCompanyKeyOrderNo, documentSuffix.
            - Do NOT guess or invent values.
            - Always ask the user for a concise remark explaining the rejection reason.
            - The remark will be truncated to 30 characters as required by the backend API.
            """,
            generateOutputSchema = true
    )
    public PurchaseOrderActionResult rejectPurchaseOrder(
            @McpToolParam(description = "JDE documentOrderTypeCode, e.g. 'OP'.")
            String documentOrderTypeCode,
            @McpToolParam(description = "JDE documentOrderInvoiceNumber, e.g. 5067.")
            Integer documentOrderInvoiceNumber,
            @McpToolParam(description = "JDE documentCompanyKeyOrderNo, e.g. '00001'.")
            String documentCompanyKeyOrderNo,
            @McpToolParam(description = "JDE documentSuffix, e.g. '000'.")
            String documentSuffix,
            @McpToolParam(description = "Short rejection remark (will be truncated to 30 characters).")
            String remark,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        log.info("Tool 'jde_reject_purchase_order' called with correlation ID: {}", correlationIdContext.extractAndSet(meta));

        return processPurchaseOrderInternal(
                "R",
                documentOrderTypeCode,
                documentOrderInvoiceNumber,
                documentCompanyKeyOrderNo,
                documentSuffix,
                remark,
                meta,
                exchange
        );
    }

    // =========================
    // Método interno común
    // =========================
    private PurchaseOrderActionResult processPurchaseOrderInternal(
            String action, // "A" o "R"
            String documentOrderTypeCode,
            Integer documentOrderInvoiceNumber,
            String documentCompanyKeyOrderNo,
            String documentSuffix,
            String remark,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        long safeInvoiceNumber = documentOrderInvoiceNumber != null ? documentOrderInvoiceNumber.longValue() : 0L;

        // Autorización: decidir sobre una OC exige el rol de aprobador en Keycloak.
        // Listar y ver detalle quedan abiertos a cualquier usuario autenticado.
        if (!roleGuard.hasRealmRole(approverRole)) {
            log.warn("Intento de {} OC {}-{} sin el rol '{}'",
                    "A".equals(action) ? "aprobar" : "rechazar",
                    documentOrderTypeCode, documentOrderInvoiceNumber, approverRole);
            return new PurchaseOrderActionResult(ToolStatus.UNAUTHORIZED,
                    "You are not authorized to " + ("A".equals(action) ? "approve" : "reject")
                            + " purchase orders: your user does not have the '" + approverRole
                            + "' role in Keycloak. Ask your administrator to assign it "
                            + "(Keycloak console -> Users -> your user -> Role mapping) and log in again.",
                    action, safe(documentOrderTypeCode), safeInvoiceNumber,
                    safe(documentCompanyKeyOrderNo), safe(documentSuffix));
        }

        String safeRemark = (remark != null) ? remark.trim() : "";
        if (safeRemark.length() > 30) {
            safeRemark = safeRemark.substring(0, 30);
        }

        progressNotifications.send(exchange, meta, 0, null,
                "Enviando la decisión a JDE, puede tardar unos segundos...");

        try {
            jdeClient.processPurchaseOrder(
                    action,
                    documentOrderTypeCode,
                    documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo,
                    documentSuffix,
                    safeRemark
            );
            return new PurchaseOrderActionResult(ToolStatus.OK, "",
                    action, safe(documentOrderTypeCode), safeInvoiceNumber,
                    safe(documentCompanyKeyOrderNo), safe(documentSuffix));
        } catch (IllegalStateException e) {
            log.warn("Purchase order not cached action={} type={} number={} company={} suffix={}: {}",
                    action, documentOrderTypeCode, documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo, documentSuffix, e.getMessage());

            return new PurchaseOrderActionResult(ToolStatus.FAILED, e.getMessage(),
                    action, safe(documentOrderTypeCode), safeInvoiceNumber,
                    safe(documentCompanyKeyOrderNo), safe(documentSuffix));
        } catch (Exception e) {
            log.error("Error processing purchase order action={} type={} number={} company={} suffix={}",
                    action, documentOrderTypeCode, documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo, documentSuffix, e);

            return new PurchaseOrderActionResult(ToolStatus.FAILED,
                    "An error occurred while trying to process the purchase order "
                            + documentOrderTypeCode + " " + documentOrderInvoiceNumber + " / "
                            + documentCompanyKeyOrderNo + "-" + documentSuffix + " with action '" + action
                            + "'. Technical details have been logged in the MCP server.",
                    action, safe(documentOrderTypeCode), safeInvoiceNumber,
                    safe(documentCompanyKeyOrderNo), safe(documentSuffix));
        }
    }
}
