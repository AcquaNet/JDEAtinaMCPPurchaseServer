package com.atina.jdeMCPServer.salesorder.tools;

import com.atina.jdeMCPServer.mcp.McpProgressNotifications;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTask;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTaskRegistry;
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

@Component
public class JdeItemTools {

    private static final Logger log = LoggerFactory.getLogger(JdeItemTools.class);

    private final JdeSalesOrderClient soClient;
    private final String defaultUnitOfMeasure;
    private final String defaultProcessingVersion;
    private final McpProgressNotifications progressNotifications;
    private final LongRunningTaskRegistry taskRegistry;
    private final boolean asyncItemSearchEnabled;
    private final long initialWaitSeconds;
    private final long gatewayTimeoutMinutes;
    private final long defaultPollIntervalMs;

    public JdeItemTools(
            JdeSalesOrderClient soClient,
            @Value("${jde.pricing.default-unit-of-measure:EA}") String defaultUnitOfMeasure,
            @Value("${jde.pricing.default-processing-version:ZJDE0001}") String defaultProcessingVersion,
            McpProgressNotifications progressNotifications,
            LongRunningTaskRegistry taskRegistry,
            @Value("${jde.item-search.async.enabled:true}") boolean asyncItemSearchEnabled,
            @Value("${jde.item-search.async.initial-wait-seconds:8}") long initialWaitSeconds,
            @Value("${jde.atina.gateway.timeout-minutes:10}") long gatewayTimeoutMinutes,
            @Value("${jde.mcp.tasks.default-poll-interval-ms:5000}") long defaultPollIntervalMs) {
        this.soClient = soClient;
        this.defaultUnitOfMeasure = defaultUnitOfMeasure;
        this.defaultProcessingVersion = defaultProcessingVersion;
        this.progressNotifications = progressNotifications;
        this.taskRegistry = taskRegistry;
        this.asyncItemSearchEnabled = asyncItemSearchEnabled;
        this.initialWaitSeconds = initialWaitSeconds;
        this.gatewayTimeoutMinutes = gatewayTimeoutMinutes;
        this.defaultPollIntervalMs = defaultPollIntervalMs;
    }

    // =========================================================================
    // Tool 3: Búsqueda de artículos (nombre -> itemId)
    // =========================================================================
    @McpTool(
            name = "jde_search_items",
            description = """
            Search JDE inventory items by (part of) their description and return matching item IDs.

            PURPOSE:
            - This tool is the ENTRY POINT to identify an article/item. Its only job is to resolve
              an item description (or a fragment of it) into one or more JDE item IDs (itemId).
            - It does NOT return price or availability. Use the itemId it returns to call
              jde_get_item_price, which DOES need it.

            WHEN TO USE:
            - The user refers to an article by name or description (e.g. "Bike, Mountain Red")
              but has not provided an itemId.
            - jde_get_item_price needs an itemId and you only have a description. Call this FIRST
              to resolve it, then chain the itemId into the next tool.

            INPUT:
            - itemSearchText: the item name/description or a fragment of it (e.g. "Bike, Mountain
              Red"). The search is a partial match, so a single fragment may return several items.

            IMPORTANT FOR THE ASSISTANT:
            - This query can take a while against a busy JDE environment. If the response says the
              search is still in progress, this is NOT an error -- simply call jde_search_items
              again with the exact same itemSearchText after a short pause (the suggested wait is
              included in the response) until you get the actual results. Never report "still in
              progress" to the user as a failure.
            - Never invent or guess itemId values. Use ONLY the values returned by this tool.
            - The relevant fields in the response are, for each entry under
              listaDeValores.itemSearchDetails[]:
                • Item ID     -> itemId
                • Description -> itemDescription1 (trimmed)
            - Present the results in a **Markdown table**, one row per item, with columns:
                • Item ID (itemId)
                • Description (itemDescription1, trimmed)
            - The tool can return ONE or MANY matches:
                • If exactly one item matches, state it clearly and offer to continue (e.g. check
                  its price for a customer).
                • If several match, list them all and ASK the user which itemId they mean before
                  chaining into jde_get_item_price. Do not assume the first one.
                • If none match, say so and ask the user to refine the search text.
            """
    )
    public String searchItems(
            @McpToolParam(
                    description = "Item name/description or a fragment of it, e.g. 'Bike, Mountain Red'. Partial match; may return several items."
            )
            String itemSearchText,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        if (itemSearchText == null || itemSearchText.isBlank()) {
            return "Please provide an item name (or part of it) to search for.";
        }

        String searchText = itemSearchText.trim();
        log.info("Searching items by text '{}'", searchText);

        if (!asyncItemSearchEnabled) {
            progressNotifications.send(exchange, meta, 0, null,
                    "Buscando artículos en JDE, puede tardar unos segundos...");
            try {
                String response = soClient.searchItems(searchText);
                return """
                       Items matching "%s":
                       %s
                       """.formatted(searchText, response);
            } catch (Exception e) {
                log.error("Error searching items with text '{}'", searchText, e);
                return """
                       An error occurred while searching items with text "%s".
                       Technical details have been logged in the MCP server.
                       Ask the user to try again later or contact support.
                       """.formatted(searchText);
            }
        }

        String token = soClient.resolveSessionToken();
        String key = "item-search|" + searchText;

        LongRunningTask task = taskRegistry.getOrStart(
                key,
                Duration.ofMinutes(gatewayTimeoutMinutes + 1),
                defaultPollIntervalMs,
                Duration.ofSeconds(initialWaitSeconds),
                () -> soClient.searchItemsWithToken(searchText, token));

        return switch (task.status()) {
            case WORKING, INPUT_REQUIRED -> """
                    The search for items matching "%s" is still in progress (this can take a while \
                    against a busy JDE environment). Call jde_search_items again with the exact same \
                    itemSearchText in about %d seconds to get the results -- this is not an error.\
                    """.formatted(searchText,
                    (task.pollIntervalMs() != null ? task.pollIntervalMs() : defaultPollIntervalMs) / 1000);
            case COMPLETED -> """
                    Items matching "%s":
                    %s
                    """.formatted(searchText, (String) task.result());
            case FAILED -> {
                log.error("Error searching items with text '{}': {}", searchText, task.error());
                yield """
                      An error occurred while searching items with text "%s".
                      Technical details have been logged in the MCP server.
                      Ask the user to try again later or contact support.
                      """.formatted(searchText);
            }
            case CANCELLED -> "The search was cancelled. Call jde_search_items again to retry.";
        };
    }

    // =========================================================================
    // Tool 4: Precio (y opcionalmente disponibilidad) de un artículo para un cliente
    // =========================================================================
    @McpTool(
            name = "jde_get_item_price",
            description = """
            Get the price of a JDE item for a specific customer, optionally including warehouse
            stock availability.

            PURPOSE:
            - Returns the unit price and extended price (unit price x quantity) of an item for a
              given customer, business unit (warehouse) and quantity. When availability is
              requested, it also returns stock availability broken down by warehouse.

            WHEN TO USE:
            - The user wants to know the price of an article for a customer, with or without
              availability.
            - Before calling this tool you need:
                • entityId: the customer's AB Number. If you only have a name, resolve it first
                  with jde_lookup_customer_by_name.
                • itemId: the item's JDE id. If you only have a description, resolve it first with
                  jde_search_items.
                • currencyCode: the customer's currency. If unknown, resolve it with
                  jde_get_customer_detail (field invoice.currencyCode / currencyCodeTransaction).

            INPUT:
            - entityId: customer AB Number, e.g. 4242. Required.
            - businessUnit: JDE business unit / warehouse code, e.g. "10". Required — always ask
              the user which warehouse if not provided. Sent to JDE exactly as given, with no
              reformatting.
            - itemId: JDE item id, e.g. 60011. Required.
            - currencyCode: customer's currency code, e.g. "USD". Required.
            - quantity: quantity requested, e.g. 2. Required.
            - unitOfMeasure: unit of measure code, e.g. "EA". Optional — if not provided, a server
              default is used.
            - processingVersion: JDE processing version, e.g. "ZJDE0001". Optional — if not
              provided, a server default is used.
            - returnAvailability: "Y" to also return warehouse stock availability, "N" for price
              only. Optional, defaults to "N".

            IMPORTANT FOR THE ASSISTANT:
            - Never invent entityId, itemId, businessUnit or currencyCode. Resolve them via the
              other tools first and confirm with the user if ambiguous.
            - If the user did not say whether they want availability, ask them (Y/N) before
              calling this tool, unless the context makes it obvious.
            - When returnAvailability = "Y", the response is under listaDeValores.product:
                • Unit Price             -> priceUnit
                • Extended Price         -> priceExtended
                • Availability, one row per warehouse under availability[]:
                    - Warehouse       -> warehouse.warehouse
                    - Warehouse Name  -> warehouse.address.mailingName (trimmed)
                    - Qty Available   -> quantityAvailable
              Present the price as a key/value Markdown table, and availability (if present) as
              its own Markdown table with columns Warehouse, Warehouse Name, Qty Available.
            - When returnAvailability = "N", the response is directly under listaDeValores:
                • Unit Price     -> priceUnitDomestic
                • Extended Price -> priceExtendedDomestic
              Present these as a key/value Markdown table.
            - Always state the currency alongside any price shown.
            """
    )
    public String getItemPrice(
            @McpToolParam(description = "Customer AB Number (entityId), from jde_lookup_customer_by_name, e.g. 4242.")
            Integer entityId,
            @McpToolParam(description = "JDE business unit / warehouse code, e.g. '10'. Sent to JDE exactly as given.")
            String businessUnit,
            @McpToolParam(description = "JDE item id, from jde_search_items, e.g. 60011.")
            Integer itemId,
            @McpToolParam(description = "Customer currency code, e.g. 'USD'.")
            String currencyCode,
            @McpToolParam(description = "Quantity requested, e.g. 2.")
            Double quantity,
            @McpToolParam(description = "Unit of measure code, e.g. 'EA'. Optional; if omitted, a server default is used.")
            String unitOfMeasure,
            @McpToolParam(description = "JDE processing version, e.g. 'ZJDE0001'. Optional; if omitted, a server default is used.")
            String processingVersion,
            @McpToolParam(description = "'Y' to also return warehouse availability, 'N' for price only. Optional, defaults to 'N'.")
            String returnAvailability,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        if (entityId == null || entityId <= 0) {
            return "Please provide a valid customer AB Number (positive integer). "
                    + "If you only have the customer name, look it up first with jde_lookup_customer_by_name.";
        }
        if (businessUnit == null || businessUnit.isBlank()) {
            return "Please provide the business unit / warehouse code to price this item against.";
        }
        if (itemId == null || itemId <= 0) {
            return "Please provide a valid item id (positive integer). "
                    + "If you only have the item description, look it up first with jde_search_items.";
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            return "Please provide the customer's currency code (e.g. 'USD').";
        }
        if (quantity == null || quantity <= 0) {
            return "Please provide a valid quantity (greater than zero).";
        }

        String uom = (unitOfMeasure != null && !unitOfMeasure.isBlank()) ? unitOfMeasure : defaultUnitOfMeasure;
        String procVersion = (processingVersion != null && !processingVersion.isBlank())
                ? processingVersion : defaultProcessingVersion;
        boolean withAvailability = "Y".equalsIgnoreCase(returnAvailability);

        log.info("Requesting item price for itemId {} / entityId {} / businessUnit '{}' (availability={})",
                itemId, entityId, businessUnit, withAvailability);

        progressNotifications.send(exchange, meta, 0, null,
                "Consultando precio en JDE, puede tardar unos segundos...");

        try {
            String response = withAvailability
                    ? soClient.getItemPriceAndAvailability(itemId, businessUnit, entityId, currencyCode, quantity, uom, procVersion)
                    : soClient.getCustomerItemPrice(itemId, businessUnit, entityId, currencyCode, quantity, uom, procVersion);

            return """
                   Price for item %d / customer %d (business unit '%s', availability=%s):
                   %s
                   """.formatted(itemId, entityId, businessUnit, withAvailability ? "Y" : "N", response);

        } catch (Exception e) {
            log.error("Error retrieving price for itemId {} / entityId {}", itemId, entityId, e);

            return """
                   An error occurred while retrieving the price for item %d / customer %d.
                   Technical details have been logged in the MCP server.
                   Ask the user to try again later or contact support.
                   """.formatted(itemId, entityId);
        }
    }
}
