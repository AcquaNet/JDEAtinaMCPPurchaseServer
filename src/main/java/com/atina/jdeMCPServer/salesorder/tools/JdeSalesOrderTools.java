package com.atina.jdeMCPServer.salesorder.tools;

import com.atina.jdeMCPServer.mcp.McpProgressNotifications;
import com.atina.jdeMCPServer.mcp.CorrelationIdContext;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTask;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTaskRegistry;
import com.atina.jdeMCPServer.salesorder.model.CustomerDetail;
import com.atina.jdeMCPServer.salesorder.model.CustomerDetailResult;
import com.atina.jdeMCPServer.salesorder.model.CustomerLookupResult;
import com.atina.jdeMCPServer.salesorder.model.CustomerSummary;
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

import java.time.Duration;
import java.util.List;

@Component
public class JdeSalesOrderTools {

    private static final Logger log = LoggerFactory.getLogger(JdeSalesOrderTools.class);

    private final JdeSalesOrderClient soClient;
    private final McpProgressNotifications progressNotifications;
    private final LongRunningTaskRegistry taskRegistry;
    private final boolean asyncCustomerLookupEnabled;
    private final long customerLookupInitialWaitSeconds;
    private final boolean asyncCustomerDetailEnabled;
    private final long customerDetailInitialWaitSeconds;
    private final long gatewayTimeoutMinutes;
    private final long defaultPollIntervalMs;
    private final CorrelationIdContext correlationIdContext;


    public JdeSalesOrderTools(
            JdeSalesOrderClient soClient,
            McpProgressNotifications progressNotifications,
            LongRunningTaskRegistry taskRegistry,
            @Value("${jde.customer-lookup.async.enabled:true}") boolean asyncCustomerLookupEnabled,
            @Value("${jde.customer-lookup.async.initial-wait-seconds:8}") long customerLookupInitialWaitSeconds,
            @Value("${jde.customer-detail.async.enabled:true}") boolean asyncCustomerDetailEnabled,
            @Value("${jde.customer-detail.async.initial-wait-seconds:8}") long customerDetailInitialWaitSeconds,
            @Value("${jde.atina.gateway.timeout-minutes:10}") long gatewayTimeoutMinutes,
            @Value("${jde.mcp.tasks.default-poll-interval-ms:5000}") long defaultPollIntervalMs,
            CorrelationIdContext correlationIdContext) {
        this.soClient = soClient;
        this.progressNotifications = progressNotifications;
        this.taskRegistry = taskRegistry;
        this.correlationIdContext = correlationIdContext;
        this.asyncCustomerLookupEnabled = asyncCustomerLookupEnabled;
        this.customerLookupInitialWaitSeconds = customerLookupInitialWaitSeconds;
        this.asyncCustomerDetailEnabled = asyncCustomerDetailEnabled;
        this.customerDetailInitialWaitSeconds = customerDetailInitialWaitSeconds;
        this.gatewayTimeoutMinutes = gatewayTimeoutMinutes;
        this.defaultPollIntervalMs = defaultPollIntervalMs;
    }

    // =========================================================================
    // Tool 1: Consulta de Cliente (nombre -> AB Number)
    // =========================================================================
    @McpTool(
            name = "jde_lookup_customer_by_name",
            description = """
            Look up JDE customers by (part of) their name and return their Address Book numbers (AB Number).

            PURPOSE:
            - This tool is the ENTRY POINT to identify a customer. Its only job is to resolve a
              customer name (or a fragment of it) into one or more Address Book numbers (entityId / AB Number).
            - It does NOT return prices, credit, addresses or balances. Use the AB Number it returns
              to call other tools that DO need it, such as the customer detail tool or the
              price/availability tool.

            WHEN TO USE:
            - The user refers to a customer by name (e.g. "the customer Capital", "Capital System")
              but has not provided an AB Number.
            - Any other tool needs a customer AB Number and you only have a name. Call this FIRST to
              resolve it, then chain the AB Number into the next tool.

            INPUT:
            - entityName: the customer name or a fragment of it (e.g. "Capital"). The search is a
              partial match, so a single fragment may return several customers.
            - limit: maximum number of customers to return (for example 5 or 10). If not provided
              or invalid, defaults to 10. Use smaller limits to keep the list manageable.

            OUTPUT (structured JSON, see outputSchema):
            - status "IN_PROGRESS": the search is still running against a busy JDE environment. This
              is NOT an error -- call jde_lookup_customer_by_name again with the exact same
              entityName after pollAfterSeconds to get the actual results. Never report this to the
              user as a failure.
            - status "OK": customers[] has the matches (capped at limit), each with name and
              addressBookNumber.
            - status "FAILED": message explains the error; retrying is safe.
            - status "INVALID_REQUEST": entityName was missing or blank.

            IMPORTANT FOR THE ASSISTANT:
            - Never invent or guess AB Numbers. Use ONLY the values returned by this tool.
            - customers[] can have ONE or MANY entries:
                • If exactly one customer matches, state it clearly and offer to continue
                  (e.g. get the customer detail or a price for an article).
                • If several match, list them all and ASK the user which addressBookNumber they mean
                  before chaining into any follow-up tool. Do not assume the first one.
                • If none match, say so and ask the user to refine or confirm the name.
                • If customers[] has exactly `limit` entries, more matches may exist beyond the
                  limit -- consider asking the user to refine the name or raise the limit.
            """,
            generateOutputSchema = true
    )
    public CustomerLookupResult lookupCustomerByName(
            @McpToolParam(
                    description = "Customer name or a fragment of it, e.g. 'Capital'. Partial match; may return several customers."
            )
            String entityName,
            @McpToolParam(
                    description = "Maximum number of customers to return (for example 5 or 10). "
                            + "If not provided or invalid, defaults to 10.",
                    required = false
            )
            Integer limit,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        // Extract correlation ID from MCP _meta and store in context
        String clientCorrelationId = meta != null ? (String) meta.get("correlationId") : null;
        correlationIdContext.setCorrelationId(clientCorrelationId);
        log.info("Tool 'jde_lookup_customer_by_name' called with correlation ID: {}", correlationIdContext.getCorrelationId());
        
        if (entityName == null || entityName.isBlank()) {
            return new CustomerLookupResult(ToolStatus.INVALID_REQUEST,
                    "Please provide a customer name (or part of it) to search for.", 0, List.of());
        }

        String name = entityName.trim();
        log.info("Looking up customers by name '{}'", name);

        if (!asyncCustomerLookupEnabled) {
            progressNotifications.send(exchange, meta, 0, null,
                    "Buscando clientes en JDE, puede tardar unos segundos...");
            try {
                List<CustomerSummary> customers = soClient.lookupAddressBookByName(name);
                return new CustomerLookupResult(ToolStatus.OK, "", 0, JdeSalesOrderClient.applyLimit(customers, limit));
            } catch (Exception e) {
                log.error("Error looking up customers by name '{}'", name, e);
                return new CustomerLookupResult(ToolStatus.FAILED,
                        "An error occurred while looking up customers with name \"" + name + "\". "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, List.of());
            }
        }

        String token = soClient.resolveSessionToken();
        String key = "customer-lookup|" + name;

        LongRunningTask task = taskRegistry.getOrStart(
                key,
                Duration.ofMinutes(gatewayTimeoutMinutes + 1),
                defaultPollIntervalMs,
                Duration.ofSeconds(customerLookupInitialWaitSeconds),
                () -> soClient.lookupAddressBookByNameWithToken(name, token));

        return switch (task.status()) {
            case WORKING, INPUT_REQUIRED -> new CustomerLookupResult(
                    ToolStatus.IN_PROGRESS,
                    "The search for customers matching \"" + name + "\" is still in progress (this can "
                            + "take a while against a busy JDE environment). Call jde_lookup_customer_by_name "
                            + "again with the exact same entityName after pollAfterSeconds to get the "
                            + "results -- this is not an error.",
                    (int) ((task.pollIntervalMs() != null ? task.pollIntervalMs() : defaultPollIntervalMs) / 1000),
                    List.of());
            case COMPLETED -> {
                @SuppressWarnings("unchecked")
                List<CustomerSummary> customers = (List<CustomerSummary>) task.result();
                yield new CustomerLookupResult(ToolStatus.OK, "", 0, JdeSalesOrderClient.applyLimit(customers, limit));
            }
            case FAILED -> {
                log.error("Error looking up customers by name '{}': {}", name, task.error());
                yield new CustomerLookupResult(ToolStatus.FAILED,
                        "An error occurred while looking up customers with name \"" + name + "\". "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, List.of());
            }
            case CANCELLED -> new CustomerLookupResult(ToolStatus.CANCELLED,
                    "The search was cancelled. Call jde_lookup_customer_by_name again to retry.", 0, List.of());
        };
    }

    // =========================================================================
    // Tool 2: Consulta del detalle de un cliente (AB Number -> detalle completo)
    // =========================================================================
    @McpTool(
            name = "jde_get_customer_detail",
            description = """
            Retrieve the full detail of a JDE customer by its Address Book number (AB Number / entityId).

            PURPOSE:
            - Returns rich master data for a single customer: name and tax id, address, financial
              amounts (credit limit, open amount, due amount), billing instructions, company,
              credit information, currency, language.

            WHEN TO USE:
            - The user wants to inspect a customer's master data, address, credit or billing setup.
            - You already know the customer's AB Number. If you only have a name, call
              jde_lookup_customer_by_name FIRST to resolve the AB Number, then call this tool.

            INPUT:
            - entityId: the customer's Address Book number (AB Number), e.g. 4242.

            OUTPUT (structured JSON, see outputSchema):
            - status "IN_PROGRESS": the query is still running against a busy JDE environment. This
              is NOT an error -- call jde_get_customer_detail again with the exact same entityId
              after pollAfterSeconds to get the actual result. Never report this to the user as a
              failure.
            - status "OK": customer has the full detail -- name, taxId, company, currencyCode,
              languageCode, address (addressLine1..4, city, stateCode, postalCode, countryCode),
              credit (creditLimit, openAmount, dueAmount, creditManagerCode,
              creditCheckLevelCode, holdCode).
            - status "INVALID_REQUEST": entityId was missing or invalid.
            - status "FAILED": message explains the error; retrying is safe.

            IMPORTANT FOR THE ASSISTANT:
            - Never invent or guess an AB Number. If it is missing or ambiguous, resolve it with
              jde_lookup_customer_by_name and confirm with the user when several customers match.
            - After presenting the detail, add a short 1-3 sentence summary of the customer's
              standing (e.g. large open balance, near/over credit limit, on hold) and offer a
              relevant next step, such as checking price/availability of an article for this
              customer.
            """,
            generateOutputSchema = true
    )
    public CustomerDetailResult getCustomerDetail(
            @McpToolParam(
                    description = "JDE customer Address Book number (AB Number / entityId), e.g. 4242."
            )
            Integer entityId,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        // Extract correlation ID from MCP _meta and store in context
        String clientCorrelationId = meta != null ? (String) meta.get("correlationId") : null;
        correlationIdContext.setCorrelationId(clientCorrelationId);
        log.info("Tool 'jde_get_customer_detail' called with correlation ID: {}", correlationIdContext.getCorrelationId());
        
        if (entityId == null || entityId <= 0) {
            return new CustomerDetailResult(ToolStatus.INVALID_REQUEST,
                    "Please provide a valid customer AB Number (positive integer). "
                            + "If you only have the customer name, look it up first with jde_lookup_customer_by_name.",
                    0, CustomerDetail.empty());
        }

        log.info("Requesting customer detail for entityId {}", entityId);

        if (!asyncCustomerDetailEnabled) {
            progressNotifications.send(exchange, meta, 0, null,
                    "Consultando el detalle del cliente en JDE, puede tardar unos segundos...");
            try {
                CustomerDetail detail = soClient.getCustomerDetail(entityId);
                return new CustomerDetailResult(ToolStatus.OK, "", 0, detail);
            } catch (Exception e) {
                log.error("Error retrieving customer detail for entityId {}", entityId, e);
                return new CustomerDetailResult(ToolStatus.FAILED,
                        "An error occurred while retrieving the detail for customer AB Number " + entityId + ". "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, CustomerDetail.empty());
            }
        }

        String token = soClient.resolveSessionToken();
        String key = "customer-detail|" + entityId;

        LongRunningTask task = taskRegistry.getOrStart(
                key,
                Duration.ofMinutes(gatewayTimeoutMinutes + 1),
                defaultPollIntervalMs,
                Duration.ofSeconds(customerDetailInitialWaitSeconds),
                () -> soClient.getCustomerDetailWithToken(entityId, token));

        return switch (task.status()) {
            case WORKING, INPUT_REQUIRED -> new CustomerDetailResult(
                    ToolStatus.IN_PROGRESS,
                    "The detail query for customer AB Number " + entityId + " is still in progress (this "
                            + "can take a while against a busy JDE environment). Call jde_get_customer_detail "
                            + "again with the exact same entityId after pollAfterSeconds to get the result -- "
                            + "this is not an error.",
                    (int) ((task.pollIntervalMs() != null ? task.pollIntervalMs() : defaultPollIntervalMs) / 1000),
                    CustomerDetail.empty());
            case COMPLETED -> {
                CustomerDetail detail = (CustomerDetail) task.result();
                yield new CustomerDetailResult(ToolStatus.OK, "", 0, detail);
            }
            case FAILED -> {
                log.error("Error retrieving customer detail for entityId {}: {}", entityId, task.error());
                yield new CustomerDetailResult(ToolStatus.FAILED,
                        "An error occurred while retrieving the detail for customer AB Number " + entityId + ". "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, CustomerDetail.empty());
            }
            case CANCELLED -> new CustomerDetailResult(ToolStatus.CANCELLED,
                    "The query was cancelled. Call jde_get_customer_detail again to retry.",
                    0, CustomerDetail.empty());
        };
    }
}
