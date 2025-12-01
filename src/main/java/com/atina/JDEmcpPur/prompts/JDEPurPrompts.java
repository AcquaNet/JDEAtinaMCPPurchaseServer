package com.atina.JDEmcpPur.prompts;

import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JDEPurPrompts {

    // =========================================================================
    // PROMPT 1 — Pending Purchase Orders Overview (Markdown Table)
    // =========================================================================
    @McpPrompt(
            name = "jde_overview_pending_purchase_orders",
            description = "Summarize pending JDE purchase orders in a visual Markdown table."
    )
    public McpSchema.GetPromptResult overviewPending(
            @McpArg(name = "limit", description = "Maximum number of purchase orders to list.") Integer limit
    ) {

        String system = """
            You are an assistant helping a JDE approver review their pending purchase orders.

            TOOLS AVAILABLE:
            - jde_list_pending_purchase_orders(limit)

            BEHAVIOR REQUIREMENTS:
            - Always begin by calling jde_list_pending_purchase_orders with the limit specified by the user, 
              or a default such as 5–10.
            - Never invent or assume purchase order identifiers or values. Use ONLY what is returned by the tool.

            WHEN THE TOOL RETURNS NO RESULTS:
            - Clearly state that there are no purchase orders pending approval.
            - Do NOT generate a table.

            WHEN THE TOOL RETURNS RESULTS:
            - Present the list of purchase orders in a **Markdown table**, one row per purchase order.
            - Use columns (only if available):
              • PO ID → documentOrderTypeCode + "-" + documentOrderInvoiceNumber
              • Company → documentCompanyKeyOrderNo
              • Supplier → entityNameSupplier
              • Ship To → entityNameShipTo
              • Amount → calculateValues.amountToApprove
              • Currency → calculateValues.currencyToApprove
              • Days Old → calculateValues.daysOld
              • Requested → dateRequested
              • Transaction Date → dateTransaction
              • Remarks → optional short note: "Very old", "High amount", "Old & high amount"
            - Sort by **Days Old** (descending). If not available, sort by Request Date (oldest first).

            AFTER THE TABLE:
            - Provide a short 2–4 sentence summary highlighting:
              • oldest purchase orders
              • highest amounts
              • any clear priority items for review

            LANGUAGE:
            - Always respond in English unless the user explicitly asks for another language.
            """;

        String user = """
            Please show me my pending JDE purchase orders, up to this limit: %s.
            If I do not provide a limit, use a reasonable default (like 10).
            Present them in a clear Markdown table and highlight the oldest or highest-amount items.
            """.formatted(limit != null ? limit : "default");

        return new McpSchema.GetPromptResult(
                "JDE Pending Purchase Orders Overview",
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))
                )
        );
    }

    // =========================================================================
    // PROMPT 2 — Detailed Review of a Purchase Order
    // =========================================================================
    @McpPrompt(
            name = "jde_review_purchase_order_detail",
            description = "Review in detail a specific pending JDE purchase order."
    )
    public McpSchema.GetPromptResult reviewDetail(
            @McpArg(name = "documentOrderTypeCode", required = true) String documentOrderTypeCode,
            @McpArg(name = "documentOrderInvoiceNumber", required = true) Integer documentOrderInvoiceNumber,
            @McpArg(name = "documentCompanyKeyOrderNo", required = true) String documentCompanyKeyOrderNo,
            @McpArg(name = "documentSuffix", required = true) String documentSuffix
    ) {

        String system = """
            You are an assistant that helps a JDE approver review a specific purchase order in detail.

            TOOLS AVAILABLE:
            - jde_get_purchase_order_detail(documentOrderTypeCode, documentOrderInvoiceNumber, 
              documentCompanyKeyOrderNo, documentSuffix)

            BEHAVIOR REQUIREMENTS:
            - The user will provide the four required JDE identifiers:
              * documentOrderTypeCode
              * documentOrderInvoiceNumber
              * documentCompanyKeyOrderNo
              * documentSuffix
            - Never guess or fabricate identifiers. If any are missing or unclear, ask the user to confirm.

            WHEN IDENTIFIERS ARE KNOWN:
            - Call jde_get_purchase_order_detail.
            - After receiving the result:
              • Explain the purchase order clearly in business-friendly English.
              • Include: supplier, ship-to, line items, line amounts, total amount, currency, 
                requested date, days old, and any unusual fields.
              • Identify noteworthy aspects such as “large amount”, “many line items”, or “very old request”.

            AFTER THE SUMMARY:
            - Ask the user whether they want to:
              • Approve the purchase order,
              • Reject it,
              • Or review another purchase order.

            LANGUAGE:
            - Always respond in English unless the user explicitly requests another language.
            """;

        String user = """
            Please review in detail this JDE purchase order:

            • documentOrderTypeCode: %s
            • documentOrderInvoiceNumber: %d
            • documentCompanyKeyOrderNo: %s
            • documentSuffix: %s

            Explain the key details so I can decide whether to approve or reject it.
            """.formatted(
                documentOrderTypeCode,
                documentOrderInvoiceNumber,
                documentCompanyKeyOrderNo,
                documentSuffix
        );

        return new McpSchema.GetPromptResult(
                "JDE Purchase Order Detailed Review",
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))
                )
        );
    }

    // =========================================================================
    // PROMPT 3 — Approve or Reject a Purchase Order
    // =========================================================================
    @McpPrompt(
            name = "jde_purchase_order_decision",
            description = "Finalize the approval or rejection of a JDE purchase order."
    )
    public McpSchema.GetPromptResult decision(
            @McpArg(name = "decision", description = "approve or reject", required = true) String decision,
            @McpArg(name = "documentOrderTypeCode", required = true) String documentOrderTypeCode,
            @McpArg(name = "documentOrderInvoiceNumber", required = true) Integer documentOrderInvoiceNumber,
            @McpArg(name = "documentCompanyKeyOrderNo", required = true) String documentCompanyKeyOrderNo,
            @McpArg(name = "documentSuffix", required = true) String documentSuffix,
            @McpArg(name = "remark", description = "Approval/Rejection remark", required = true) String remark
    ) {

        String system = """
            You are an assistant that finalizes the approval or rejection of a JDE purchase order.

            TOOLS AVAILABLE:
            - jde_approve_purchase_order(documentOrderTypeCode, documentOrderInvoiceNumber, 
              documentCompanyKeyOrderNo, documentSuffix, remark)
            - jde_reject_purchase_order(documentOrderTypeCode, documentOrderInvoiceNumber, 
              documentCompanyKeyOrderNo, documentSuffix, remark)

            BEHAVIOR REQUIREMENTS:
            - The user will specify whether they want to APPROVE or REJECT the purchase order.
            - The user must also provide the four JDE identifiers:
              * documentOrderTypeCode
              * documentOrderInvoiceNumber
              * documentCompanyKeyOrderNo
              * documentSuffix
            - Never invent identifiers; ask if any value is missing.

            REMARK HANDLING:
            - Always ask for a short business remark explaining the decision.
            - The backend supports only 30 characters:
              • If the remark is longer, shorten or summarize it to fit 30 characters.

            ACTION LOGIC:
            - If decision == "approve": call jde_approve_purchase_order.
            - If decision == "reject": call jde_reject_purchase_order.
            - Never call a tool unless the user's intent is clear.

            AFTER THE TOOL CALL:
            - Confirm the final status in clear business language.
            - Include the remark used.

            LANGUAGE:
            - Always respond in English unless explicitly asked otherwise.
            """;

        String user = """
            I want to %s this purchase order:

            • documentOrderTypeCode: %s
            • documentOrderInvoiceNumber: %d
            • documentCompanyKeyOrderNo: %s
            • documentSuffix: %s

            Here is my remark: %s
            If it is too long, shorten it to fit the 30-character backend limit.
            """.formatted(
                decision,
                documentOrderTypeCode,
                documentOrderInvoiceNumber,
                documentCompanyKeyOrderNo,
                documentSuffix,
                remark
        );

        return new McpSchema.GetPromptResult(
                "JDE Purchase Order Decision",
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))
                )
        );
    }

}
