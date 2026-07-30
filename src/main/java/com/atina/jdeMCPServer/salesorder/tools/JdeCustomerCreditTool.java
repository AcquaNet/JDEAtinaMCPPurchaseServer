package com.atina.jdeMCPServer.salesorder.tools;

import com.atina.jdeMCPServer.mcp.McpProgressNotifications;
import com.atina.jdeMCPServer.salesorder.model.CustomerCreditInfoResult;
import com.atina.jdeMCPServer.salesorder.model.CustomerCreditSummary;
import com.atina.jdeMCPServer.salesorder.model.ToolStatus;
import com.atina.jdeMCPServer.salesorder.services.JdeSalesOrderClient;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class JdeCustomerCreditTool {

    private static final Logger log = LoggerFactory.getLogger(JdeCustomerCreditTool.class);

    private final JdeSalesOrderClient soClient;
    private final McpProgressNotifications progressNotifications;

    public JdeCustomerCreditTool(JdeSalesOrderClient soClient, McpProgressNotifications progressNotifications) {
        this.soClient = soClient;
        this.progressNotifications = progressNotifications;
    }

    @McpTool(
            name = "jde_get_customer_credit_info",
            description = """
                Retrieve the credit limit and financial exposure of a JDE customer.

                Returns the customer's credit limit, total exposure amount, whether the customer is
                exempt from credit holds, and its tax id.

                Use this tool when the user wants to:
                - Check a customer's credit status or debt.
                - Know the credit limit and current exposure for a customer.
                - Evaluate a customer's financial risk before processing a sales order.

                INPUT:
                - entityId: the customer's Address Book number (AB Number), e.g. 4242. If you only
                  have a name, resolve it first with jde_lookup_customer_by_name.

                OUTPUT (structured JSON, see outputSchema):
                - status "OK": creditLimit, totalExposure, creditHoldExempt and taxId are populated.
                - status "INVALID_REQUEST": entityId was missing or invalid.
                - status "FAILED": message explains the error; retrying is safe.

                IMPORTANT FOR THE ASSISTANT:
                - Never guess or invent an AB Number. Ask the user, or resolve it first with
                  jde_lookup_customer_by_name, if not provided.
                - Present the result as a key/value Markdown table: Customer Number, Credit Limit,
                  Total Exposure, Credit Hold Exempt, Tax ID, and a computed Available Credit
                  (Credit Limit - Total Exposure).
                - If Total Exposure exceeds Credit Limit, highlight that the customer is over their
                  credit limit.
                - Provide a short 1-2 sentence summary of the customer's credit standing.
                """,
            generateOutputSchema = true
    )
    public CustomerCreditInfoResult getCustomerCreditInfo(
            @McpToolParam(description = "JDE customer number (Address Book AB Number / entityId), e.g. 4242.")
            Integer entityId,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        if (entityId == null || entityId <= 0) {
            return new CustomerCreditInfoResult(ToolStatus.INVALID_REQUEST,
                    "Please provide a valid customer number (positive integer).",
                    entityId != null ? entityId : 0, BigDecimal.ZERO, BigDecimal.ZERO, false, "");
        }

        log.info("Requesting credit info for customer {}", entityId);

        progressNotifications.send(exchange, meta, 0, null,
                "Consultando crédito del cliente en JDE, puede tardar unos segundos...");

        try {
            CustomerCreditSummary credit = soClient.getCustomerCreditInfo(entityId);
            return new CustomerCreditInfoResult(ToolStatus.OK, "", entityId,
                    credit.creditLimit(), credit.totalExposure(), credit.creditHoldExempt(), credit.taxId());

        } catch (Exception e) {
            log.error("Error retrieving credit info for customer {}", entityId, e);

            return new CustomerCreditInfoResult(ToolStatus.FAILED,
                    "An error occurred while retrieving credit information for customer " + entityId + ". "
                            + "Technical details have been logged in the MCP server. "
                            + "Ask the user to try again later or contact support.",
                    entityId, BigDecimal.ZERO, BigDecimal.ZERO, false, "");
        }
    }
}
