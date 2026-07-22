package com.atina.jdeMCPServer.salesorder.prompts;

import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JDESalesOrderPrompts {

    // =========================================================================
    // PROMPT 1 — Look up a customer by name (name -> AB Number)
    // =========================================================================
    @McpPrompt(
            name = "jde_lookup_customer_by_name",
            description = "Resolve a customer name into its JDE Address Book number(s) (AB Number)."
    )
    public McpSchema.GetPromptResult lookupCustomerByName(
            @McpArg(name = "entityName", description = "Customer name or a fragment of it.", required = true)
            String entityName
    ) {

        String system = """
            You are an assistant that helps identify JDE customers by name.

            TOOLS AVAILABLE:
            - jde_lookup_customer_by_name(entityName)

            BEHAVIOR REQUIREMENTS:
            - Call jde_lookup_customer_by_name with the name (or fragment) provided by the user.
            - This tool ONLY resolves a name into Address Book numbers (AB Number). It does not
              return prices, credit or addresses.
            - Never invent or assume AB Numbers. Use ONLY the values returned by the tool.

            WHEN THE TOOL RETURNS NO RESULTS:
            - Clearly state that no customer matches that name and ask the user to refine or confirm it.
            - Do NOT generate a table.

            WHEN THE TOOL RETURNS RESULTS:
            - Present the matches in a **Markdown table**, one row per customer, with columns:
              • Customer Name -> entityName (trimmed)
              • AB Number -> entity.entityId
            - If exactly one customer matches, state it clearly and offer a next step (customer detail
              or price/availability of an article for that customer).
            - If several customers match, list them all and ASK which AB Number the user means before
              doing anything else. Do NOT assume the first one.

            LANGUAGE:
            - Always respond in English unless the user explicitly asks for another language.
            """;

        String user = """
            Please find the JDE customers whose name contains: "%s".
            Show their names and AB Numbers in a Markdown table. If there is more than one match,
            ask me which one I mean before continuing.
            """.formatted(entityName);

        return new McpSchema.GetPromptResult(
                "JDE Customer Lookup by Name",
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))
                )
        );
    }

    // =========================================================================
    // PROMPT 2 — Customer detail by AB Number
    // =========================================================================
    @McpPrompt(
            name = "jde_review_customer_detail",
            description = "Review the full master data of a JDE customer by its AB Number."
    )
    public McpSchema.GetPromptResult reviewCustomerDetail(
            @McpArg(name = "entityId", description = "Customer AB Number (Address Book number).", required = true)
            Integer entityId
    ) {

        String system = """
            You are an assistant that helps review the master data of a JDE customer.

            TOOLS AVAILABLE:
            - jde_lookup_customer_by_name(entityName)
            - jde_get_customer_detail(entityId)

            BEHAVIOR REQUIREMENTS:
            - If you already have the customer's AB Number (entityId), call jde_get_customer_detail with it.
            - If the user only gives a name, FIRST call jde_lookup_customer_by_name to resolve the AB Number.
              If several customers match, ask the user which one before calling the detail tool.
            - Never invent AB Numbers or customer data. Use ONLY what the tools return.

            WHEN THE DETAIL IS RETURNED:
            - The detail is under listaDeValores.customerResults[0]. Trim padding spaces from text values.
            - Present it grouped in **Markdown tables**:
              1) GENERAL: Name (entityName), AB Number (entity.entityId), Tax ID (entity.entityTaxId),
                 Company (company), Currency (invoice.currencyCode), Language (languageCode).
              2) ADDRESS: Address (address.addressLine1..4), City (address.city), State (address.stateCode),
                 Postal Code (address.postalCode), Country (address.countryCode).
              3) CREDIT & AMOUNTS: Credit Limit (amounts.amountCreditLimit), Open Amount (amounts.amountOpen),
                 Due Amount (amounts.amountDue), Credit Manager (credit.creditManagerCode),
                 Credit Check Level (billingInstructions.creditCheckLevelCode), Hold Code (billingInstructions.holdCode).

            AFTER THE TABLES:
            - Add a short summary of the customer's standing (large open balance, near/over credit
              limit, on hold, etc.) and offer a relevant next step.

            LANGUAGE:
            - Always respond in English unless the user explicitly requests another language.
            """;

        String user = """
            Please show me the full detail of the JDE customer with AB Number: %s.
            If I gave you a name instead of a number, resolve it first with jde_lookup_customer_by_name
            and confirm with me if there is more than one match.
            """.formatted(entityId != null ? entityId : "(not provided)");

        return new McpSchema.GetPromptResult(
                "JDE Customer Detail Review",
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))
                )
        );
    }
}
