package com.atina.jdeMCPServer.salesorder.tools;

import com.atina.jdeMCPServer.mcp.McpProgressNotifications;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTask;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTaskRegistry;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

@Component
public class JdeCustomerCreditTool {

    private static final Logger log = LoggerFactory.getLogger(JdeCustomerCreditTool.class);

    private final JdeSalesOrderClient soClient;
    private final McpProgressNotifications progressNotifications;
    private final LongRunningTaskRegistry taskRegistry;
    private final boolean asyncCustomerCreditEnabled;
    private final long initialWaitSeconds;
    private final long gatewayTimeoutMinutes;
    private final long defaultPollIntervalMs;

    public JdeCustomerCreditTool(
            JdeSalesOrderClient soClient,
            McpProgressNotifications progressNotifications,
            LongRunningTaskRegistry taskRegistry,
            @Value("${jde.customer-credit.async.enabled:true}") boolean asyncCustomerCreditEnabled,
            @Value("${jde.customer-credit.async.initial-wait-seconds:8}") long initialWaitSeconds,
            @Value("${jde.atina.gateway.timeout-minutes:10}") long gatewayTimeoutMinutes,
            @Value("${jde.mcp.tasks.default-poll-interval-ms:5000}") long defaultPollIntervalMs) {
        this.soClient = soClient;
        this.progressNotifications = progressNotifications;
        this.taskRegistry = taskRegistry;
        this.asyncCustomerCreditEnabled = asyncCustomerCreditEnabled;
        this.initialWaitSeconds = initialWaitSeconds;
        this.gatewayTimeoutMinutes = gatewayTimeoutMinutes;
        this.defaultPollIntervalMs = defaultPollIntervalMs;
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
                - status "IN_PROGRESS": the query is still running against a busy JDE environment.
                  This is NOT an error -- call jde_get_customer_credit_info again with the exact same
                  entityId after pollAfterSeconds to get the actual result. Never report this to the
                  user as a failure.
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
                    0, entityId != null ? entityId : 0, BigDecimal.ZERO, BigDecimal.ZERO, false, "");
        }

        log.info("Requesting credit info for customer {}", entityId);

        if (!asyncCustomerCreditEnabled) {
            progressNotifications.send(exchange, meta, 0, null,
                    "Consultando crédito del cliente en JDE, puede tardar unos segundos...");
            try {
                CustomerCreditSummary credit = soClient.getCustomerCreditInfo(entityId);
                return new CustomerCreditInfoResult(ToolStatus.OK, "", 0, entityId,
                        credit.creditLimit(), credit.totalExposure(), credit.creditHoldExempt(), credit.taxId());
            } catch (Exception e) {
                log.error("Error retrieving credit info for customer {}", entityId, e);
                return new CustomerCreditInfoResult(ToolStatus.FAILED,
                        "An error occurred while retrieving credit information for customer " + entityId + ". "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, entityId, BigDecimal.ZERO, BigDecimal.ZERO, false, "");
            }
        }

        String token = soClient.resolveSessionToken();
        String key = "customer-credit|" + entityId;

        LongRunningTask task = taskRegistry.getOrStart(
                key,
                Duration.ofMinutes(gatewayTimeoutMinutes + 1),
                defaultPollIntervalMs,
                Duration.ofSeconds(initialWaitSeconds),
                () -> soClient.getCustomerCreditInfoWithToken(entityId, token));

        return switch (task.status()) {
            case WORKING, INPUT_REQUIRED -> new CustomerCreditInfoResult(
                    ToolStatus.IN_PROGRESS,
                    "The credit info query for customer AB Number " + entityId + " is still in progress "
                            + "(this can take a while against a busy JDE environment). Call "
                            + "jde_get_customer_credit_info again with the exact same entityId after "
                            + "pollAfterSeconds to get the result -- this is not an error.",
                    (int) ((task.pollIntervalMs() != null ? task.pollIntervalMs() : defaultPollIntervalMs) / 1000),
                    entityId, BigDecimal.ZERO, BigDecimal.ZERO, false, "");
            case COMPLETED -> {
                CustomerCreditSummary credit = (CustomerCreditSummary) task.result();
                yield new CustomerCreditInfoResult(ToolStatus.OK, "", 0, entityId,
                        credit.creditLimit(), credit.totalExposure(), credit.creditHoldExempt(), credit.taxId());
            }
            case FAILED -> {
                log.error("Error retrieving credit info for customer {}: {}", entityId, task.error());
                yield new CustomerCreditInfoResult(ToolStatus.FAILED,
                        "An error occurred while retrieving credit information for customer " + entityId + ". "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, entityId, BigDecimal.ZERO, BigDecimal.ZERO, false, "");
            }
            case CANCELLED -> new CustomerCreditInfoResult(ToolStatus.CANCELLED,
                    "The query was cancelled. Call jde_get_customer_credit_info again to retry.",
                    0, entityId, BigDecimal.ZERO, BigDecimal.ZERO, false, "");
        };
    }
}
