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

    // Si tenés un servicio que llama al microservicio Atina, lo inyectás acá.
    // private final JdePurchaseService jdePurchaseService;
    //
    // public JdePurchaseApprovalTool(JdePurchaseService jdePurchaseService) {
    //     this.jdePurchaseService = jdePurchaseService;
    // }

    public JdePurchaseApprovalTool(JdePurchaseOrderClient jdeClient) {
        this.jdeClient = jdeClient;
    }


    // =========================
    // Tool 1: Listar pendientes
    // =========================
    @McpTool(
            name = "jde_list_pending_purchase_orders",
            description = """
           List JDE purchase orders that are pending approval for the current approver.
           Use this tool to show the user which purchase orders are waiting for their approval
           before asking them to approve a specific one.
           The result should be a concise, human-readable list.
           """
    )
    public String getPendingPurchaseOrders(
            @McpToolParam(
                    description = "Maximum number of pending purchase orders to return (e.g. 10)."
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
    // Tool 2: Aprobar una OC
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