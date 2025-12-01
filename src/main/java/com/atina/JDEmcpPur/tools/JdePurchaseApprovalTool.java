package com.atina.JDEmcpPur.tools;

import com.atina.JDEmcpPur.services.JdePurchaseOrderClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class JdePurchaseApprovalTool {

    private static final Logger log = LoggerFactory.getLogger(JdePurchaseApprovalTool.class);

    private final JdePurchaseOrderClient jdeClient;
    private final ObjectMapper objectMapper;


    public JdePurchaseApprovalTool(JdePurchaseOrderClient jdeClient, ObjectMapper objectMapper) {
        this.jdeClient = jdeClient;
        this.objectMapper = objectMapper;
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

        IMPORTANT FOR THE ASSISTANT:
        - Always call this tool BEFORE asking the user to approve or reject a specific purchase order.
        - Do NOT invent or assume purchase order numbers or details. Use exactly the values returned by this tool.
        - Each purchase order in JDE is uniquely identified by the combination of:
          * documentOrderTypeCode
          * documentOrderInvoiceNumber
          * documentCompanyKeyOrderNo
          * documentSuffix
        - After calling this tool, summarize the result as a short, human-readable list
          (e.g., one line per purchase order with following values: documentCompanyKeyOrderNo,documentOrderTypeCode,documentOrderInvoiceNumber,entityNameSupplier,entityNameShipTo,calculateValues.amountToApprove,calculateValues.currencyToApprove, calculateValues.daysOld,dateRequested and dateTransaction ).
        - If no purchase orders are returned, clearly explain to the user that there are no pending approvals.""")
    public String getPendingPurchaseOrders(
            @McpToolParam(
                    description = """
                Maximum number of pending purchase orders to return (for example 5 or 10).
                If not provided or invalid, default to 10.
                Use smaller limits (like 5–10) to keep the list readable for the user."""
            )
            Integer limit
    ) {

        int finalLimit = (limit != null && limit > 0) ? limit : 10;

        var orders = jdeClient.getPendingPurchaseOrders(limit);

        return """
               Pending purchase orders (showing up to %d):
               %s
               """.formatted(limit != null ? limit : 10, orders);
    }

    // =========================
    // Tool 2: Ver detalle de una OC
    // =========================
    @McpTool(
            name = "jde_get_purchase_order_detail",
            description = """
            Retrieve detailed information for a specific JDE purchase order that is pending approval.

            Use this tool when:
            - The user has already seen the list of pending purchase orders.
            - The user wants to inspect one specific purchase order in more detail
              before deciding whether to approve or reject it.

            IMPORTANT FOR THE ASSISTANT:
            - You MUST provide all four JDE identifiers exactly as returned by the
              pending-orders tool:
              * documentOrderTypeCode
              * documentOrderInvoiceNumber
              * documentCompanyKeyOrderNo
              * documentSuffix
            - Do NOT guess or fabricate values. Always use values that came from a
              previous tool call or from the user.
            - After calling this tool, summarize the result for the user in a
              clear, human-friendly way (for example: supplier, lines, amounts,
              currency, requested date, etc.)."""
    )
    public String getPurchaseOrderDetail(
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
            String documentSuffix
    ) {

        log.info(
                "Requesting detail for PO type={} number={} company={} suffix={}",
                documentOrderTypeCode, documentOrderInvoiceNumber,
                documentCompanyKeyOrderNo, documentSuffix
        );

        try {
            // Llamada real al microservicio a través del cliente
            var detail = jdeClient.getPurchaseOrderDetail(
                    documentOrderTypeCode,
                    documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo,
                    documentSuffix
            );

            // 'detail' se asume como String (JSON o texto) que el tool devuelve al modelo
            return """
                    Detail for purchase order %s %d / %s-%s:

                    %s
                    """.formatted(
                    documentOrderTypeCode,
                    documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo,
                    documentSuffix,
                    detail
            );

        } catch (Exception e) {
            log.error(
                    "Error retrieving detail for PO type={} number={} company={} suffix={}",
                    documentOrderTypeCode, documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo, documentSuffix, e
            );

            return """
                    An error occurred while trying to retrieve the details \
                    of purchase order %s %d / %s-%s.
                    Technical details have been logged in the MCP server.
                    Ask the user to try again later or contact support.
                    """.formatted(
                    documentOrderTypeCode,
                    documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo,
                    documentSuffix
            );
        }
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

            IMPORTANT FOR THE ASSISTANT:
            - You MUST provide all four JDE identifiers exactly as returned by the pending-orders or detail tool:
              * documentOrderTypeCode
              * documentOrderInvoiceNumber
              * documentCompanyKeyOrderNo
              * documentSuffix
            - Do NOT guess or invent values.
            - Ask the user for a short remark explaining the approval reason if appropriate.
            - The remark will be truncated to 30 characters as required by the backend API.
            """
    )
    public String approvePurchaseOrder(
            @McpToolParam(description = "JDE documentOrderTypeCode, e.g. 'OP'.")
            String documentOrderTypeCode,
            @McpToolParam(description = "JDE documentOrderInvoiceNumber, e.g. 5067.")
            Integer documentOrderInvoiceNumber,
            @McpToolParam(description = "JDE documentCompanyKeyOrderNo, e.g. '00001'.")
            String documentCompanyKeyOrderNo,
            @McpToolParam(description = "JDE documentSuffix, e.g. '000'.")
            String documentSuffix,
            @McpToolParam(description = "Short approval remark (will be truncated to 30 characters).")
            String remark
    ) {
        return processPurchaseOrderInternal(
                "A",
                documentOrderTypeCode,
                documentOrderInvoiceNumber,
                documentCompanyKeyOrderNo,
                documentSuffix,
                remark
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

            IMPORTANT FOR THE ASSISTANT:
            - You MUST provide all four JDE identifiers exactly as returned by the pending-orders or detail tool:
              * documentOrderTypeCode
              * documentOrderInvoiceNumber
              * documentCompanyKeyOrderNo
              * documentSuffix
            - Do NOT guess or invent values.
            - Always ask the user for a concise remark explaining the rejection reason.
            - The remark will be truncated to 30 characters as required by the backend API.
            """
    )
    public String rejectPurchaseOrder(
            @McpToolParam(description = "JDE documentOrderTypeCode, e.g. 'OP'.")
            String documentOrderTypeCode,
            @McpToolParam(description = "JDE documentOrderInvoiceNumber, e.g. 5067.")
            Integer documentOrderInvoiceNumber,
            @McpToolParam(description = "JDE documentCompanyKeyOrderNo, e.g. '00001'.")
            String documentCompanyKeyOrderNo,
            @McpToolParam(description = "JDE documentSuffix, e.g. '000'.")
            String documentSuffix,
            @McpToolParam(description = "Short rejection remark (will be truncated to 30 characters).")
            String remark
    ) {
        return processPurchaseOrderInternal(
                "R",
                documentOrderTypeCode,
                documentOrderInvoiceNumber,
                documentCompanyKeyOrderNo,
                documentSuffix,
                remark
        );
    }

    // =========================
    // Método interno común
    // =========================
    private String processPurchaseOrderInternal(
            String action, // "A" o "R"
            String documentOrderTypeCode,
            Integer documentOrderInvoiceNumber,
            String documentCompanyKeyOrderNo,
            String documentSuffix,
            String remark
    ) {

        String safeRemark = (remark != null) ? remark.trim() : "";
        if (safeRemark.length() > 30) {
            safeRemark = safeRemark.substring(0, 30);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("documentCompanyKeyOrderNo", documentCompanyKeyOrderNo);
        payload.put("documentOrderTypeCode", documentOrderTypeCode);
        payload.put("documentOrderInvoiceNumber", documentOrderInvoiceNumber);
        payload.put("documentSuffix", documentSuffix);
        payload.put("action", action);
        payload.put("remark", safeRemark);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Error serializing purchase order payload", e);
            return """
                   Failed to serialize the purchase order request payload.
                   Please try again or contact support.
                   """;
        }

        try {
            String response = jdeClient.processPurchaseOrder(jsonBody);
            return """
                   Purchase order %s %d / %s-%s has been processed with action '%s'.
                   Backend response:
                   %s
                   """.formatted(
                    documentOrderTypeCode,
                    documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo,
                    documentSuffix,
                    action,
                    response
            );
        } catch (Exception e) {
            log.error("Error processing purchase order action={} type={} number={} company={} suffix={}",
                    action, documentOrderTypeCode, documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo, documentSuffix, e);

            return """
                   An error occurred while trying to process the purchase order %s %d / %s-%s \
                   with action '%s'. Technical details have been logged in the MCP server.
                   """.formatted(
                    documentOrderTypeCode,
                    documentOrderInvoiceNumber,
                    documentCompanyKeyOrderNo,
                    documentSuffix,
                    action
            );
        }
    }
}