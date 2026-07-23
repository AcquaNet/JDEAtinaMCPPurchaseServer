package com.atina.jdeMCPServer.salesorder.tools;

import com.atina.jdeMCPServer.mcp.McpProgressNotifications;
import com.atina.jdeMCPServer.salesorder.services.JdeSalesOrderClient;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class JdeCustomerCreditTool {

    private static final Logger log = LoggerFactory.getLogger(JdeCustomerCreditTool.class);

    private final JdeSalesOrderClient soClient;

    public JdeCustomerCreditTool(JdeSalesOrderClient soClient) {
        this.soClient = soClient;
    }

    @McpTool(
            name = "jde_get_customer_credit_info",
            description = """
                Retrieve the credit and financial exposure information for a JDE customer.

                Returns the customer's credit limit, total exposure amount, currency, and company.

                Use this tool when the user wants to:
                - Check a customer's credit status or debt.
                - Know the credit limit and current exposure for a customer.
                - Evaluate a customer's financial risk before processing a sales order.

                IMPORTANT FOR THE ASSISTANT:
                - The user must provide the customer number (address book number in JDE).
                - Never guess or invent customer numbers. Ask the user if not provided.
                - After receiving the result, present it in a clear Markdown table with key/value pairs:
                  • Customer Number
                  • Company
                  • Currency
                  • Credit Limit
                  • Total Exposure
                  • Available Credit (Credit Limit - Total Exposure)
                - If Total Exposure exceeds Credit Limit, highlight that the customer is over their credit limit.
                - Provide a short 1-2 sentence summary of the customer's credit standing.
                """
    )
    public String getCustomerCreditInfo(
            @McpToolParam(description = "JDE customer number (address book number), e.g. 4242")
            Integer customerNumber,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        if (customerNumber == null || customerNumber <= 0) {
            return "Please provide a valid customer number (positive integer).";
        }

        log.info("Requesting credit info for customer {}", customerNumber);

        McpProgressNotifications.send(exchange, meta, 0, null,
                "Consultando crédito del cliente en JDE, puede tardar unos segundos...");

        try {
            String response = soClient.getCustomerCreditFinancialInfo(customerNumber);

            return """
                   Customer credit and financial info for customer %d:
                   %s
                   """.formatted(customerNumber, response);

        } catch (Exception e) {
            log.error("Error retrieving credit info for customer {}", customerNumber, e);

            return """
                   An error occurred while retrieving credit information for customer %d.
                   Technical details have been logged in the MCP server.
                   Ask the user to try again later or contact support.
                   """.formatted(customerNumber);
        }
    }
}
