package com.atina.jdeMCPServer.salesorder.tools;

import com.atina.jdeMCPServer.mcp.McpProgressNotifications;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTask;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTaskRegistry;
import com.atina.jdeMCPServer.salesorder.model.ItemPriceResult;
import com.atina.jdeMCPServer.salesorder.model.ItemSearchResult;
import com.atina.jdeMCPServer.salesorder.model.ItemSummary;
import com.atina.jdeMCPServer.salesorder.model.PriceQuote;
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
import java.util.List;

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
            - limit: maximum number of items to return (for example 5 or 10). If not provided or
              invalid, defaults to 10. Use smaller limits to keep the list manageable.

            OUTPUT (structured JSON, see outputSchema):
            - status "IN_PROGRESS": the search is still running against a busy JDE environment. This
              is NOT an error -- call jde_search_items again with the exact same itemSearchText after
              pollAfterSeconds to get the actual results. Never report this to the user as a failure.
            - status "OK": items[] has the matches (capped at limit), each with itemId and description.
            - status "FAILED" / "CANCELLED": message explains what happened; retrying is safe.
            - status "INVALID_REQUEST": itemSearchText was missing or blank.

            IMPORTANT FOR THE ASSISTANT:
            - Never invent or guess itemId values. Use ONLY the values returned by this tool.
            - items[] can have ONE or MANY entries:
                • If exactly one item matches, state it clearly and offer to continue (e.g. check
                  its price for a customer).
                • If several match, list them all and ASK the user which itemId they mean before
                  chaining into jde_get_item_price. Do not assume the first one.
                • If none match, say so and ask the user to refine the search text.
                • If items[] has exactly `limit` entries, more matches may exist beyond the limit --
                  consider asking the user to refine the search text or raise the limit.
            """,
            generateOutputSchema = true
    )
    public ItemSearchResult searchItems(
            @McpToolParam(
                    description = "Item name/description or a fragment of it, e.g. 'Bike, Mountain Red'. Partial match; may return several items."
            )
            String itemSearchText,
            @McpToolParam(
                    description = "Maximum number of items to return (for example 5 or 10). "
                            + "If not provided or invalid, defaults to 10.",
                    required = false
            )
            Integer limit,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        if (itemSearchText == null || itemSearchText.isBlank()) {
            return new ItemSearchResult(ToolStatus.INVALID_REQUEST,
                    "Please provide an item name (or part of it) to search for.", 0, List.of());
        }

        String searchText = itemSearchText.trim();
        log.info("Searching items by text '{}'", searchText);

        if (!asyncItemSearchEnabled) {
            progressNotifications.send(exchange, meta, 0, null,
                    "Buscando artículos en JDE, puede tardar unos segundos...");
            try {
                List<ItemSummary> items = soClient.searchItems(searchText);
                return new ItemSearchResult(ToolStatus.OK, "", 0, JdeSalesOrderClient.applyLimit(items, limit));
            } catch (Exception e) {
                log.error("Error searching items with text '{}'", searchText, e);
                return new ItemSearchResult(ToolStatus.FAILED,
                        "An error occurred while searching items with text \"" + searchText + "\". "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, List.of());
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
            case WORKING, INPUT_REQUIRED -> new ItemSearchResult(
                    ToolStatus.IN_PROGRESS,
                    "The search for items matching \"" + searchText + "\" is still in progress (this can "
                            + "take a while against a busy JDE environment). Call jde_search_items again "
                            + "with the exact same itemSearchText after pollAfterSeconds to get the "
                            + "results -- this is not an error.",
                    (int) ((task.pollIntervalMs() != null ? task.pollIntervalMs() : defaultPollIntervalMs) / 1000),
                    List.of());
            case COMPLETED -> {
                @SuppressWarnings("unchecked")
                List<ItemSummary> items = (List<ItemSummary>) task.result();
                yield new ItemSearchResult(ToolStatus.OK, "", 0, JdeSalesOrderClient.applyLimit(items, limit));
            }
            case FAILED -> {
                log.error("Error searching items with text '{}': {}", searchText, task.error());
                yield new ItemSearchResult(ToolStatus.FAILED,
                        "An error occurred while searching items with text \"" + searchText + "\". "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, List.of());
            }
            case CANCELLED -> new ItemSearchResult(ToolStatus.CANCELLED,
                    "The search was cancelled. Call jde_search_items again to retry.", 0, List.of());
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

            OUTPUT (structured JSON, see outputSchema):
            - status "OK": unitPrice / extendedPrice / currencyCode are always populated.
              availability[] (warehouseCode, warehouseName, quantityAvailable) is populated only
              when returnAvailability = "Y" was requested; otherwise it is an empty array.
            - status "INVALID_REQUEST": message explains which required input is missing/invalid.
            - status "FAILED": message explains the error; retrying is safe.

            IMPORTANT FOR THE ASSISTANT:
            - Never invent entityId, itemId, businessUnit or currencyCode. Resolve them via the
              other tools first and confirm with the user if ambiguous.
            - If the user did not say whether they want availability, ask them (Y/N) before
              calling this tool, unless the context makes it obvious.
            - Always state the currency alongside any price shown.
            """,
            generateOutputSchema = true
    )
    public ItemPriceResult getItemPrice(
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
        int safeItemId = itemId != null ? itemId : 0;
        int safeEntityId = entityId != null ? entityId : 0;
        String safeBusinessUnit = businessUnit != null ? businessUnit : "";
        String safeCurrencyCode = currencyCode != null ? currencyCode : "";

        if (entityId == null || entityId <= 0) {
            return new ItemPriceResult(ToolStatus.INVALID_REQUEST,
                    "Please provide a valid customer AB Number (positive integer). "
                            + "If you only have the customer name, look it up first with jde_lookup_customer_by_name.",
                    safeItemId, safeEntityId, safeBusinessUnit, safeCurrencyCode, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
        if (businessUnit == null || businessUnit.isBlank()) {
            return new ItemPriceResult(ToolStatus.INVALID_REQUEST,
                    "Please provide the business unit / warehouse code to price this item against.",
                    safeItemId, safeEntityId, safeBusinessUnit, safeCurrencyCode, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
        if (itemId == null || itemId <= 0) {
            return new ItemPriceResult(ToolStatus.INVALID_REQUEST,
                    "Please provide a valid item id (positive integer). "
                            + "If you only have the item description, look it up first with jde_search_items.",
                    safeItemId, safeEntityId, safeBusinessUnit, safeCurrencyCode, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            return new ItemPriceResult(ToolStatus.INVALID_REQUEST,
                    "Please provide the customer's currency code (e.g. 'USD').",
                    safeItemId, safeEntityId, safeBusinessUnit, safeCurrencyCode, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
        if (quantity == null || quantity <= 0) {
            return new ItemPriceResult(ToolStatus.INVALID_REQUEST,
                    "Please provide a valid quantity (greater than zero).",
                    safeItemId, safeEntityId, safeBusinessUnit, safeCurrencyCode, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
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
            PriceQuote quote = withAvailability
                    ? soClient.getItemPriceAndAvailability(itemId, businessUnit, entityId, currencyCode, quantity, uom, procVersion)
                    : soClient.getCustomerItemPrice(itemId, businessUnit, entityId, currencyCode, quantity, uom, procVersion);

            return new ItemPriceResult(ToolStatus.OK, "", itemId, entityId, businessUnit, currencyCode,
                    quote.unitPrice(), quote.extendedPrice(), quote.availability());

        } catch (Exception e) {
            log.error("Error retrieving price for itemId {} / entityId {}", itemId, entityId, e);

            return new ItemPriceResult(ToolStatus.FAILED,
                    "An error occurred while retrieving the price for item " + itemId + " / customer "
                            + entityId + ". Technical details have been logged in the MCP server. "
                            + "Ask the user to try again later or contact support.",
                    itemId, entityId, businessUnit, currencyCode, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
    }
}
