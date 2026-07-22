package com.atina.jdeMCPServer.salesorder.tools;

import com.atina.jdeMCPServer.salesorder.services.JdeSalesOrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class JdeSalesOrderTools {

    private static final Logger log = LoggerFactory.getLogger(JdeSalesOrderTools.class);

    private final JdeSalesOrderClient soClient;

    public JdeSalesOrderTools(JdeSalesOrderClient soClient) {
        this.soClient = soClient;
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

            IMPORTANT FOR THE ASSISTANT:
            - Never invent or guess AB Numbers. Use ONLY the values returned by this tool.
            - The relevant fields in the response are, for each entry under
              listaDeValores.lookupAddressBookResult[]:
                • Name     -> entityName
                • AB Number -> entity.entityId
            - Present the results in a **Markdown table**, one row per customer, with columns:
                • Customer Name (entityName, trimmed)
                • AB Number (entity.entityId)
            - The tool can return ONE or MANY matches:
                • If exactly one customer matches, state it clearly and offer to continue
                  (e.g. get the customer detail or a price for an article).
                • If several match, list them all and ASK the user which AB Number they mean before
                  chaining into any follow-up tool. Do not assume the first one.
                • If none match, say so and ask the user to refine or confirm the name.
            """
    )
    public String lookupCustomerByName(
            @McpToolParam(
                    description = "Customer name or a fragment of it, e.g. 'Capital'. Partial match; may return several customers."
            )
            String entityName
    ) {
        if (entityName == null || entityName.isBlank()) {
            return "Please provide a customer name (or part of it) to search for.";
        }

        String name = entityName.trim();
        log.info("Looking up customers by name '{}'", name);

        try {
            String response = soClient.lookupAddressBookByName(name);

            return """
                   Customers matching name "%s":
                   %s
                   """.formatted(name, response);

        } catch (Exception e) {
            log.error("Error looking up customers by name '{}'", name, e);

            return """
                   An error occurred while looking up customers with name "%s".
                   Technical details have been logged in the MCP server.
                   Ask the user to try again later or contact support.
                   """.formatted(name);
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
              credit information, currency, phone numbers, etc.

            WHEN TO USE:
            - The user wants to inspect a customer's master data, address, credit or billing setup.
            - You already know the customer's AB Number. If you only have a name, call
              jde_lookup_customer_by_name FIRST to resolve the AB Number, then call this tool.

            INPUT:
            - entityId: the customer's Address Book number (AB Number), e.g. 4242.

            IMPORTANT FOR THE ASSISTANT:
            - Never invent or guess an AB Number. If it is missing or ambiguous, resolve it with
              jde_lookup_customer_by_name and confirm with the user when several customers match.
            - The detail lives under listaDeValores.customerResults[0]. Present it grouped in
              **Markdown tables** (trim padding spaces from text values):

              1) GENERAL — key/value table:
                 • Name -> entityName
                 • AB Number -> entity.entityId
                 • Tax ID -> entity.entityTaxId
                 • Company -> company
                 • Currency -> invoice.currencyCode
                 • Language -> languageCode

              2) ADDRESS — key/value table:
                 • Address -> address.addressLine1 (+ line2..4 if present)
                 • City -> address.city
                 • State -> address.stateCode
                 • Postal Code -> address.postalCode
                 • Country -> address.countryCode

              3) CREDIT & AMOUNTS — key/value table:
                 • Credit Limit -> amounts.amountCreditLimit (or credit.amountCreditLimit)
                 • Open Amount -> amounts.amountOpen
                 • Due Amount -> amounts.amountDue
                 • Credit Manager -> credit.creditManagerCode
                 • Credit Check Level -> billingInstructions.creditCheckLevelCode
                 • Hold Code -> billingInstructions.holdCode

            AFTER THE TABLES:
            - Add a short 1-3 sentence summary of the customer's standing (e.g. large open balance,
              near/over credit limit, on hold) and offer a relevant next step, such as checking
              price/availability of an article for this customer.
            """
    )
    public String getCustomerDetail(
            @McpToolParam(
                    description = "JDE customer Address Book number (AB Number / entityId), e.g. 4242."
            )
            Integer entityId
    ) {
        if (entityId == null || entityId <= 0) {
            return "Please provide a valid customer AB Number (positive integer). "
                    + "If you only have the customer name, look it up first with jde_lookup_customer_by_name.";
        }

        log.info("Requesting customer detail for entityId {}", entityId);

        try {
            String response = soClient.getCustomerDetail(entityId);

            return """
                   Customer detail for AB Number %d:
                   %s
                   """.formatted(entityId, response);

        } catch (Exception e) {
            log.error("Error retrieving customer detail for entityId {}", entityId, e);

            return """
                   An error occurred while retrieving the detail for customer AB Number %d.
                   Technical details have been logged in the MCP server.
                   Ask the user to try again later or contact support.
                   """.formatted(entityId);
        }
    }
}
