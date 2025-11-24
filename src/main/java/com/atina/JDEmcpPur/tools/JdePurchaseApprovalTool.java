package com.atina.JDEmcpPur.tools;

import com.atina.JDEmcpPur.services.JdePurchaseOrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class JdePurchaseApprovalTool {

    private static final Logger log = LoggerFactory.getLogger(JdePurchaseApprovalTool.class);

    private final JdePurchaseOrderClient jdeClient;

    public JdePurchaseApprovalTool(JdePurchaseOrderClient jdeClient) {
        this.jdeClient = jdeClient;
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
          (e.g., one line per purchase order with key, supplier, amount, and date).
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
    // Tool 3: Aprobar una OC
    // =========================
    @McpTool(
            name = "jde_approve_purchase_order",
            description = """
           Approve a JDE purchase order that is currently pending approval.
           Use this tool only after showing the user the pending orders and confirming
           which purchase order number they want to approve.
           """
    )
    public String approvePurchaseOrder(
            @McpToolParam(
                    description = "Purchase order number to approve, for example '1233'."
            )
            String poNumber,
            @McpToolParam(
                    description = "Optional approval comment to be sent to JDE (may be null or empty)."
            )
            String approvalComment
    ) {

        log.info("Request to approve PO {} with comment: {}", poNumber, approvalComment);

        try {
            // TODO: llamada real al microservicio Atina/JDE
            // boolean approved = jdePurchaseService.approvePurchaseOrder(poNumber, approvalComment);

            boolean approved = true; // stub de ejemplo

            if (approved) {
                return """
                       Purchase order **%s** was successfully approved.
                       Comment: %s
                       """.formatted(
                        poNumber,
                        (approvalComment == null || approvalComment.isBlank())
                                ? "(no comment provided)"
                                : approvalComment
                );
            } else {
                return """
                       Purchase order **%s** could not be approved.
                       Please check its status in JDE or try again later.
                       """.formatted(poNumber);
            }

        } catch (Exception e) {
            log.error("Error approving purchase order {} in JDE", poNumber, e);
            return """
                   An error occurred while trying to approve purchase order **%s**.
                   Technical details have been logged in the MCP server. 
                   Ask the user to try again later or contact support.
                   """.formatted(poNumber);
        }
    }

    // En el futuro podés agregar más tools:
    // - Rechazar OC
    // - Ver detalle de una OC específica, etc.
}