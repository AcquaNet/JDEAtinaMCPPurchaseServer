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

    // =========================================================================
    // PROMPT 3 — Search items by name/description (name -> itemId)
    // =========================================================================
    @McpPrompt(
            name = "jde_search_items_prompt",
            description = "Resolve an item name/description into its JDE item id(s) (itemId)."
    )
    public McpSchema.GetPromptResult searchItems(
            @McpArg(name = "itemSearchText", description = "Item name/description or a fragment of it.", required = true)
            String itemSearchText
    ) {

        String system = """
            You are an assistant that helps identify JDE inventory items by name/description.

            TOOLS AVAILABLE:
            - jde_search_items(itemSearchText)

            BEHAVIOR REQUIREMENTS:
            - Call jde_search_items with the description (or fragment) provided by the user.
            - This tool ONLY resolves a description into item ids (itemId). It does not return
              price or availability.
            - Never invent or assume itemId values. Use ONLY the values returned by the tool.

            WHEN THE TOOL RETURNS NO RESULTS:
            - Clearly state that no item matches that description and ask the user to refine it.
            - Do NOT generate a table.

            WHEN THE TOOL RETURNS RESULTS:
            - Present the matches in a **Markdown table**, one row per item, with columns:
              • Item ID -> itemId
              • Description -> itemDescription1 (trimmed)
            - If exactly one item matches, state it clearly and offer a next step (check its price
              for a customer).
            - If several items match, list them all and ASK which itemId the user means before
              doing anything else. Do NOT assume the first one.

            LANGUAGE:
            - Always respond in English unless the user explicitly asks for another language.
            """;

        String user = """
            Please find the JDE items whose description contains: "%s".
            Show their item ids and descriptions in a Markdown table. If there is more than one
            match, ask me which one I mean before continuing.
            """.formatted(itemSearchText);

        return new McpSchema.GetPromptResult(
                "JDE Item Search",
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))
                )
        );
    }

    // =========================================================================
    // PROMPT 4 — Check item price (and optionally availability) for a customer
    // =========================================================================
    @McpPrompt(
            name = "jde_check_item_price",
            description = "Check the price (and optionally warehouse availability) of an item for a customer."
    )
    public McpSchema.GetPromptResult checkItemPrice(
            @McpArg(name = "customer", description = "Customer name or AB Number.", required = true)
            String customer,
            @McpArg(name = "item", description = "Item description or itemId.", required = true)
            String item
    ) {

        String system = """
            You are an assistant that helps check the price of a JDE item for a customer.

            TOOLS AVAILABLE:
            - jde_lookup_customer_by_name(entityName)
            - jde_get_customer_detail(entityId)
            - jde_search_items(itemSearchText)
            - jde_get_item_price(entityId, businessUnit, itemId, currencyCode, quantity, unitOfMeasure, processingVersion, returnAvailability)

            BEHAVIOR REQUIREMENTS:
            - Resolve the customer's AB Number (entityId): if you only have a name, call
              jde_lookup_customer_by_name first; if several match, ask the user which one.
            - Resolve the customer's currency: if unknown, call jde_get_customer_detail and read
              invoice.currencyCode / currencyCodeTransaction.
            - Resolve the item id: if you only have a description, call jde_search_items first; if
              several match, ask the user which one.
            - Ask the user for the business unit (warehouse) and the quantity if not provided —
              never invent them.
            - Ask the user whether they want warehouse availability (Y/N) if not clear from context.
            - Never invent entityId, itemId, businessUnit or currencyCode. Use ONLY values returned
              by the tools or explicitly given by the user.
            - Once all inputs are known, call jde_get_item_price.

            WHEN THE PRICE IS RETURNED:
            - If returnAvailability = "Y", the response is under listaDeValores.product: priceUnit,
              priceExtended, and availability[] (warehouse.warehouse, warehouse.address.mailingName
              trimmed, quantityAvailable). Present price as a key/value table and availability as
              its own Markdown table (Warehouse, Warehouse Name, Qty Available).
            - If returnAvailability = "N", the response is under listaDeValores: priceUnitDomestic,
              priceExtendedDomestic. Present as a key/value Markdown table.
            - Always state the currency alongside any price shown.

            LANGUAGE:
            - Always respond in English unless the user explicitly requests another language.
            """;

        String user = """
            Please check the price for item "%s" for customer "%s".
            Resolve the customer and item first if I only gave you names/descriptions, ask me for
            the warehouse, quantity and whether I want availability if you don't have them, then
            show me the price.
            """.formatted(item, customer);

        return new McpSchema.GetPromptResult(
                "JDE Item Price Check",
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))
                )
        );
    }
}
