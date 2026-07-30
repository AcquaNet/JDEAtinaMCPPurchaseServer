package com.atina.jdeMCPServer.salesorder.tools;

import com.atina.jdeMCPServer.mcp.McpProgressNotifications;
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
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JdeSalesOrderTools {

    private static final Logger log = LoggerFactory.getLogger(JdeSalesOrderTools.class);

    private final JdeSalesOrderClient soClient;
    private final McpProgressNotifications progressNotifications;

    public JdeSalesOrderTools(JdeSalesOrderClient soClient, McpProgressNotifications progressNotifications) {
        this.soClient = soClient;
        this.progressNotifications = progressNotifications;
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
        if (entityName == null || entityName.isBlank()) {
            return new CustomerLookupResult(ToolStatus.INVALID_REQUEST,
                    "Please provide a customer name (or part of it) to search for.", List.of());
        }

        String name = entityName.trim();
        log.info("Looking up customers by name '{}'", name);

        progressNotifications.send(exchange, meta, 0, null,
                "Buscando clientes en JDE, puede tardar unos segundos...");

        try {
            List<CustomerSummary> customers = soClient.lookupAddressBookByName(name);
            return new CustomerLookupResult(ToolStatus.OK, "", JdeSalesOrderClient.applyLimit(customers, limit));

        } catch (Exception e) {
            log.error("Error looking up customers by name '{}'", name, e);

            return new CustomerLookupResult(ToolStatus.FAILED,
                    "An error occurred while looking up customers with name \"" + name + "\". "
                            + "Technical details have been logged in the MCP server. "
                            + "Ask the user to try again later or contact support.",
                    List.of());
        }
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
        if (entityId == null || entityId <= 0) {
            return new CustomerDetailResult(ToolStatus.INVALID_REQUEST,
                    "Please provide a valid customer AB Number (positive integer). "
                            + "If you only have the customer name, look it up first with jde_lookup_customer_by_name.",
                    CustomerDetail.empty());
        }

        log.info("Requesting customer detail for entityId {}", entityId);

        progressNotifications.send(exchange, meta, 0, null,
                "Consultando el detalle del cliente en JDE, puede tardar unos segundos...");

        try {
            CustomerDetail detail = soClient.getCustomerDetail(entityId);
            return new CustomerDetailResult(ToolStatus.OK, "", detail);

        } catch (Exception e) {
            log.error("Error retrieving customer detail for entityId {}", entityId, e);

            return new CustomerDetailResult(ToolStatus.FAILED,
                    "An error occurred while retrieving the detail for customer AB Number " + entityId + ". "
                            + "Technical details have been logged in the MCP server. "
                            + "Ask the user to try again later or contact support.",
                    CustomerDetail.empty());
        }
    }
}
