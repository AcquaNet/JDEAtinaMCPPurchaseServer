package com.atina.jdeMCPServer.salesorder.tools;

import com.atina.jdeMCPServer.mcp.McpProgressNotifications;
import com.atina.jdeMCPServer.mcp.CorrelationIdContext;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTask;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTaskRegistry;
import com.atina.jdeMCPServer.salesorder.model.ItemListPriceEntry;
import com.atina.jdeMCPServer.salesorder.model.ItemListPriceResult;
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
    private final boolean asyncItemListPriceEnabled;
    private final long itemListPriceInitialWaitSeconds;
    private final long gatewayTimeoutMinutes;
    private final long defaultPollIntervalMs;
    private final CorrelationIdContext correlationIdContext;

    public JdeItemTools(
            JdeSalesOrderClient soClient,
            @Value("${jde.pricing.default-unit-of-measure:EA}") String defaultUnitOfMeasure,
            @Value("${jde.pricing.default-processing-version:ZJDE0001}") String defaultProcessingVersion,
            McpProgressNotifications progressNotifications,
            LongRunningTaskRegistry taskRegistry,
            @Value("${jde.item-search.async.enabled:true}") boolean asyncItemSearchEnabled,
            @Value("${jde.item-search.async.initial-wait-seconds:8}") long initialWaitSeconds,
            @Value("${jde.item-list-price.async.enabled:true}") boolean asyncItemListPriceEnabled,
            @Value("${jde.item-list-price.async.initial-wait-seconds:8}") long itemListPriceInitialWaitSeconds,
            @Value("${jde.atina.gateway.timeout-minutes:10}") long gatewayTimeoutMinutes,
            @Value("${jde.mcp.tasks.default-poll-interval-ms:5000}") long defaultPollIntervalMs,
            CorrelationIdContext correlationIdContext) {
        this.soClient = soClient;
        this.defaultUnitOfMeasure = defaultUnitOfMeasure;
        this.defaultProcessingVersion = defaultProcessingVersion;
        this.progressNotifications = progressNotifications;
        this.taskRegistry = taskRegistry;
        this.asyncItemSearchEnabled = asyncItemSearchEnabled;
        this.initialWaitSeconds = initialWaitSeconds;
        this.asyncItemListPriceEnabled = asyncItemListPriceEnabled;
        this.itemListPriceInitialWaitSeconds = itemListPriceInitialWaitSeconds;
        this.gatewayTimeoutMinutes = gatewayTimeoutMinutes;
        this.defaultPollIntervalMs = defaultPollIntervalMs;
        this.correlationIdContext = correlationIdContext;
    }

    // =========================================================================
    // Tool 3: Búsqueda de artículos (nombre -> itemId)
    // =========================================================================
    @McpTool(
            name = "jde_search_items",
            description = """
        Search JDE inventory items by all or part of their description and return matching product
        identifiers.

        PURPOSE:
        - Resolves a product name, description or description fragment into one or more JDE items.
        - Returns the identifiers needed by pricing and inventory tools:
          - itemCatalog: the external/catalog product code;
          - itemId: the internal numeric JDE item identifier.
        - It does not return prices or stock availability.
        - This tool is an intermediate product-resolution step. When the user originally requested
          a price or availability, do not treat the search result as the final answer unless the
          user must choose between multiple matching products.

        WHEN TO USE:
        - The user refers to a product by name or description instead of providing a product code.
        - The product reference contains more than one word and must therefore be treated as a
          product description.
        - jde_get_item_list_price requires an itemCatalog or itemId and only a product description
          is known.
        - jde_get_item_price requires an itemCatalog or itemId and only a product description is
          known.

        INPUT:
        - itemSearchText:
          Product name, description or description fragment.
          Example: "Mountain Bike, Red".
          The search is a partial match, so one search may return multiple products.

        - limit:
          Maximum number of products to return, for example 5 or 10.
          If omitted or invalid, defaults to 10.
          Prefer a manageable number of results.

        OUTPUT (structured JSON, see outputSchema):
        - status "IN_PROGRESS":
          The search is still running against JDE.
          This is not an error.
          Call jde_search_items again using the exact same itemSearchText and limit after
          pollAfterSeconds.
          Do not report this status as a failure.

        - status "OK":
          itemSearchDetails[] contains the matching products.

          Each result may include:
          - itemCatalog: external/catalog product code;
          - itemId: internal numeric JDE item identifier;
          - itemDescription1: primary product description;
          - itemDescription2: secondary product description;
          - itemProduct;
          - itemFreeForm;
          - itemSearchText.

        - status "FAILED" or "CANCELLED":
          The message explains what happened.
          Retry only when appropriate.

        - status "INVALID_REQUEST":
          itemSearchText was missing or blank.

        HOW TO HANDLE SEARCH RESULTS:
        - If exactly one product matches:
          - Use itemCatalog as the preferred product identifier.
          - Also use itemId when available.
          - Do not stop and merely show the identifiers when the user originally requested a price
            or stock operation.
          - Immediately continue with the appropriate tool:
            - use jde_get_item_list_price for a base/list/catalog price;
            - use jde_get_item_price for a customer-specific price or availability.
          - Only respond with the search result itself when the user explicitly asked only to
            search for or identify products.

        - If multiple products match:
          - Do not automatically select the first result.
          - Show the matching products and ask the user which one they mean.
          - For every option, display at least:
            Product Code | Item ID | Description
          - Product Code must come from itemCatalog.
          - Description must come primarily from itemDescription1.
          - Include itemDescription2 only when it contains meaningful additional text.
          - Do not call a pricing or availability tool until the user selects a product.

        - If no products match:
          - Inform the user that no matching product was found.
          - Ask them to refine the description.
          - Do not invent an itemCatalog or itemId.

        - If the number of results equals limit:
          - More matches may exist.
          - Mention this when useful and ask the user to refine the search if the displayed options
            are not sufficient.

        IMPORTANT FOR THE ASSISTANT:
        - Never invent or guess itemCatalog or itemId values.
        - Use only identifiers returned by this tool.
        - Prefer itemCatalog as the user-facing product code.
        - Treat itemId as the internal JDE identifier.
        - JDE may return fixed-width strings padded with trailing spaces.
        - Trim leading and trailing spaces from itemCatalog, itemDescription1, itemDescription2,
          itemProduct, itemFreeForm and itemSearchText before displaying or reusing them.
        - When asking the user to choose between multiple products, present a Markdown table with
          these columns:
          Product Code | Item ID | Description
        - Use itemDescription1 as the main description.
        - If itemDescription2 is not blank after trimming, append it to itemDescription1.
        - Never display only itemId when itemCatalog and itemDescription1 are available.
        - When the original request was for a base price and exactly one product matches,
          automatically call jde_get_item_list_price using itemCatalog and itemId.
        - When the original request was for a customer-specific price and exactly one product
          matches, automatically call jde_get_item_price using itemCatalog and itemId.
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
        String correlationId = correlationIdContext.extractAndSet(meta);
        log.info("Tool 'jde_search_items' called with correlation ID: {}", correlationId);

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
                correlationIdContext.wrapForBackgroundThread(correlationId,
                        () -> soClient.searchItemsWithToken(searchText, token)));

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
        Get the price of a JDE item for a specific customer, optionally including stock
        availability.

        PURPOSE:
        - Returns the customer-specific price of a product.
        - The price may depend on the customer, warehouse, quantity, currency and unit of measure.
        - When warehouse or unit of measure is not specified, the tool may return multiple price
          results.
        - When availability is requested, it also returns stock availability by warehouse.

        REQUIRED CUSTOMER IDENTIFICATION:
        - A customer must be identified before calling this tool.
        - entityId is the customer's JDE Address Book Number.
        - If the user provides only a customer name, call jde_lookup_customer_by_name first.
        - If jde_lookup_customer_by_name returns exactly one customer, continue automatically.
        - If multiple customers are returned, do not select the first one. Present the matching
          customers and ask the user which one they mean.

        REQUIRED PRODUCT IDENTIFICATION:
        - A product code must be available before calling this tool.
        - The product may be identified by:
          - itemCatalog: the external/catalog product code.
          - itemId: the internal numeric JDE item identifier.
        - itemCatalog is the preferred identifier.
        - itemId is the fallback identifier.
        - Never assume that a numeric value is necessarily an itemId.
        - itemCatalog may be numeric, alphabetic or alphanumeric.

        HOW TO INTERPRET THE USER'S PRODUCT INPUT:
        - If the user provides a product reference containing MORE THAN ONE WORD, treat it as a
          product description.
          Example: "Mountain Bike, Red".
        - For a multi-word product description, call jde_search_items first.
        - jde_search_items is only an intermediate product-resolution step. It is not the final
          answer to a customer-price request.

        - If jde_search_items returns exactly one matching product:
          - Use itemCatalog as the preferred identifier.
          - Also include itemId when available.
          - Immediately continue with the remaining required steps and call this tool.
          - Do not stop after displaying only the product identifiers.
          - Do not ask the user whether they want to continue.

        - If jde_search_items returns multiple matching products:
          - Do not automatically select the first product.
          - Present the matching products in a Markdown table.
          - Display at least:
              Product Code | Item ID | Description
          - Product Code must use itemCatalog.
          - Description must use itemDescription1.
          - If itemDescription2 contains meaningful text, append it to itemDescription1.
          - Ask the user which product they mean before calling this tool.

        - If jde_search_items returns no matching products:
          - Inform the user that no matching product was found.
          - Ask the user to refine the description.
          - Never invent an itemCatalog or itemId.

        - If the user provides a SINGLE value, treat it first as itemCatalog, regardless of
          whether it contains numbers, letters or both.
        - Do not classify a single value as a description merely because it contains letters.
          A value such as "BIKE210" may be a valid itemCatalog.
        - If querying with itemCatalog returns no matching product or no price, retry using the
          same value as itemId only when the value is a valid numeric itemId.
        - Never invent, modify, normalize or partially match a product code.

        PRODUCT IDENTIFIER SEARCH ORDER:
        1. Use the supplied value as itemCatalog.
        2. If no result is returned, retry as itemId when the value is a valid numeric itemId.
        3. If jde_search_items returned both itemCatalog and itemId, send both identifiers.
        4. Do not repeatedly alternate between itemCatalog and itemId or create an infinite retry
           loop.

        CUSTOMER CURRENCY:
        - currencyCode is optional for the user.
        - If the user provides a currency, use it exactly as provided.
        - If the user does not provide a currency, call jde_get_customer_detail using entityId.
        - Use invoice.currencyCodeTransaction as currencyCode.
        - Do not use another currency field when invoice.currencyCodeTransaction is available.
        - Never invent or assume a currency.
        - If invoice.currencyCodeTransaction is empty or unavailable, ask the user which currency
          should be used.

        WHEN TO USE:
        - The user asks for the price of a product for a specific customer.
        - The user asks for a negotiated, customer-specific or quantity-dependent price.
        - The user asks for a customer-specific price with or without stock availability.

        WHEN NOT TO USE:
        - The user asks only for the standard, base, catalog or list price without a customer.
          Use jde_get_item_list_price instead.

        INPUT:
        - entityId:
          Customer JDE Address Book Number.
          Required.
          Example: 4242.

        - itemCatalog:
          Preferred JDE product catalog code.
          May be numeric, alphabetic or alphanumeric.
          Examples: "210", "BIKE-RED", "ABC123".

        - itemId:
          Internal numeric JDE item identifier.
          Example: 60011.

        - At least one of itemCatalog or itemId is required.
        - When both are available, send both.
        - Always try itemCatalog before itemId.

        - businessUnit:
          Optional JDE warehouse/business unit.
          Example: "10".
          If provided, return the price for that warehouse.
          If omitted, return prices for all available warehouses.
          Do not ask the user for a warehouse merely because it was omitted.

        - quantity:
          Quantity requested.
          Required.
          Example: 2.
          Do not invent a quantity.
          If quantity is required and the user did not provide one, ask for it.

        - unitOfMeasure:
          Optional unit-of-measure code.
          Example: "EA".
          If provided, return prices for that unit.
          If omitted, return prices for all available units of measure.

        - currencyCode:
          Optional.
          If omitted, resolve it using jde_get_customer_detail and use
          invoice.currencyCodeTransaction.

        - processingVersion:
          Optional JDE processing version.
          Example: "ZJDE0001".
          If omitted, the server default is used.

        - returnAvailability:
          Optional.
          Use "Y" only when the user explicitly requests stock or availability.
          Use "N" when the user requests only pricing.
          Defaults to "N".
          Do not ask whether the user wants availability before a price-only request.

        REQUIRED TOOL SEQUENCE:
        1. Resolve entityId when only a customer name is known.
        2. Resolve the product using jde_search_items when a multi-word description was provided.
        3. If exactly one product is found, continue automatically.
        4. Resolve currencyCode using jde_get_customer_detail when necessary.
        5. Ask for quantity only if it is required and missing.
        6. Call this tool using itemCatalog first.
        7. If itemCatalog returns no result, retry using itemId when a valid numeric itemId is
           available.
        8. Return the complete customer-specific price.
        9. Never stop after only resolving the customer, product or currency.

        OUTPUT (structured JSON, see outputSchema):
        - status "IN_PROGRESS":
          The request is still running against JDE.
          This is not an error.
          Call jde_get_item_price again using the exact same arguments after
          pollAfterSeconds.
          Never report this as a failure.

        - status "OK":
          The response contains one or more customer-specific price results.

          When businessUnit was omitted, one result may be returned per warehouse.

          When unitOfMeasure was omitted, one result may be returned per unit of measure.

          Each price result may include:
          - businessUnit
          - warehouseName
          - itemCatalog
          - itemId
          - itemDescription
          - quantity
          - unitOfMeasure
          - unitPrice
          - extendedPrice
          - currencyCode
          - dateEffective
          - dateExpiration

          availability[] is populated only when returnAvailability = "Y".

          Each availability entry may include:
          - warehouseCode
          - warehouseName
          - quantityAvailable
          - unitOfMeasure

        - status "INVALID_REQUEST":
          The message explains which required information is missing.

        - status "FAILED":
          The message explains the error.
          Retry only when appropriate.

        IMPORTANT FOR THE ASSISTANT:
        - jde_lookup_customer_by_name, jde_search_items and jde_get_customer_detail are
          intermediate resolution tools. They are not the final answer when the user requested a
          customer-specific price.
        - When customer, product and currency have been resolved unambiguously, continue
          automatically with this tool.
        - Never ask the user whether they want to continue after a single unambiguous customer or
          product match.
        - Never answer a customer-price request with only entityId, itemCatalog, itemId or
          currencyCode.
        - Never invent entityId, itemCatalog, itemId, businessUnit, quantity, unitOfMeasure or
          currencyCode.
        - Trim leading and trailing spaces from itemCatalog, itemDescription1,
          itemDescription2 and other JDE text fields before displaying them.
        - Always identify the customer for whom the price was calculated.
        - Always tell the user which product code was used.
        - Prefer itemCatalog as the user-facing product code.
        - Include itemId as the internal JDE identifier when available.
        - Present the results as a Markdown table.
        - Use one row for every warehouse and unit-of-measure combination returned by JDE.
        - Recommended columns:
          Product Code | Item ID | Description | Warehouse | Quantity | Unit | Unit Price |
          Extended Price | Currency
        - Always display the currency alongside every price.
        - Never combine prices belonging to different warehouses, units or currencies.
        - If businessUnit was omitted, explain that prices are shown for all available warehouses.
        - If unitOfMeasure was omitted, explain that prices are shown for all available units of
          measure.
        - If currencyCode was resolved automatically, explain that the customer's transaction
          currency (invoice.currencyCodeTransaction) was used.
        - When returnAvailability is "Y", present availability in a separate table.
        - When returnAvailability is "N" or omitted, answer the price request directly and then
          suggest that stock availability can also be consulted if the user is interested.
        """,
            generateOutputSchema = true
    )
    public ItemPriceResult getItemPrice(
            @McpToolParam(
                    description = """
                Customer JDE Address Book Number. Resolve it with
                jde_lookup_customer_by_name when only the customer name is known.
                """)
            Integer entityId,

            @McpToolParam(
                    description = """
                Optional JDE business unit or warehouse code, e.g. "10".
                Send exactly as provided, without reformatting.
                If omitted, return prices for all available warehouses.
                """,
                    required = false
            )
            String businessUnit,

            @McpToolParam(
                    description = """
                Internal numeric JDE item identifier, e.g. 60011.
                At least one of itemId or itemCatalog is required.
                Use itemId as fallback when itemCatalog returns no result.
                """,
                    required = false
            )
            Integer itemId,

            @McpToolParam(
                    description = """
                Preferred JDE catalog product code, e.g. "210", "BIKE-RED" or "ABC123".
                It may be numeric, alphabetic or alphanumeric.
                At least one of itemCatalog or itemId is required.
                For a single user-provided product reference, try this parameter first.
                """,
                    required = false
            )
            String itemCatalog,

            @McpToolParam(
                    description = """
                Optional transaction currency code, e.g. "USD".
                If the user did not provide it, resolve it using jde_get_customer_detail and use
                invoice.currencyCodeTransaction.
                """,
                    required = false
            )
            String currencyCode,

            @McpToolParam(
                    description = "Quantity requested, e.g. 2. Required."
            )
            Double quantity,

            @McpToolParam(
                    description = """
                Optional unit-of-measure code, e.g. "EA".
                If omitted, return prices for all available units of measure.
                """,
                    required = false
            )
            String unitOfMeasure,

            @McpToolParam(
                    description = """
                Optional JDE processing version, e.g. "ZJDE0001".
                If omitted, the configured server default is used.
                """,
                    required = false
            )
            String processingVersion,

            @McpToolParam(
                    description = """
                Use "Y" to include stock availability and "N" for price only.
                Optional and defaults to "N".
                Do not ask about availability when the user requested only a price.
                """,
                    required = false
            )
            String returnAvailability,

            McpMeta meta,
            McpSyncServerExchange exchange
    ){
        log.info("Tool 'jde_get_item_price' called with correlation ID: {}", correlationIdContext.extractAndSet(meta));

        int safeItemId = itemId != null ? itemId : 0;
        int safeEntityId = entityId != null ? entityId : 0;
        String safeBusinessUnit = businessUnit != null ? businessUnit : "";
        String safeCurrencyCode = currencyCode != null ? currencyCode : "";
        boolean hasItemId = itemId != null && itemId > 0;
        boolean hasItemCatalog = itemCatalog != null && !itemCatalog.isBlank();

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
        if (!hasItemId && !hasItemCatalog) {
            return new ItemPriceResult(ToolStatus.INVALID_REQUEST,
                    "Please provide at least one item identifier: a valid item id (positive integer) "
                            + "or an item catalog code. If you only have the item description, look it "
                            + "up first with jde_search_items.",
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

        log.info("Requesting item price for itemId {} / itemCatalog {} / entityId {} / businessUnit '{}' (availability={})",
                itemId, itemCatalog, entityId, businessUnit, withAvailability);

        progressNotifications.send(exchange, meta, 0, null,
                "Consultando precio en JDE, puede tardar unos segundos...");

        try {
            PriceQuote quote = withAvailability
                    ? soClient.getItemPriceAndAvailability(itemId, itemCatalog, businessUnit, entityId, currencyCode, quantity, uom, procVersion)
                    : soClient.getCustomerItemPrice(itemId, itemCatalog, businessUnit, entityId, currencyCode, quantity, uom, procVersion);

            return new ItemPriceResult(ToolStatus.OK, "", safeItemId, entityId, businessUnit, currencyCode,
                    quote.unitPrice(), quote.extendedPrice(), quote.availability());

        } catch (Exception e) {
            log.error("Error retrieving price for itemId {} / itemCatalog {} / entityId {}", itemId, itemCatalog, entityId, e);

            return new ItemPriceResult(ToolStatus.FAILED,
                    "An error occurred while retrieving the price for item " + itemDescriptor(itemId, itemCatalog)
                            + " / customer " + entityId + ". Technical details have been logged in the MCP server. "
                            + "Ask the user to try again later or contact support.",
                    safeItemId, entityId, businessUnit, currencyCode, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
    }

    // =========================================================================
    // Tool 5: Precio de lista de un artículo (sin cliente)
    // =========================================================================
    @McpTool(
            name = "jde_get_item_list_price",
            description = """
        Get the JDE base/list price of an item. This is the standard catalog price and is NOT
        customer-specific.

        PURPOSE:
        - Returns the base/list price of a product.
        - The product must be identified by either:
          - itemCatalog: the external/catalog product code, or
          - itemId: the internal numeric JDE item identifier.
        - Does not require or accept a customer.
        - For customer-specific prices or stock availability, use jde_get_item_price instead.

        REQUIRED PRODUCT IDENTIFICATION:
        - A product code is required before calling this tool.
        - The preferred identifier is itemCatalog.
        - itemId is the fallback identifier.
        - Never assume that a numeric value is necessarily an itemId.
        - itemCatalog may contain only numbers, only letters, or a combination of letters,
          numbers and symbols.

        HOW TO INTERPRET THE USER'S PRODUCT INPUT:
        - If the user provides a product reference containing MORE THAN ONE WORD, treat it as a
          product description, not as a product code.
          Example: "Mountain Bike Red".
          In that case, call jde_search_items FIRST to resolve the description into itemCatalog
          and itemId.
        - If jde_search_items returns exactly one product, immediately call
          jde_get_item_list_price using the returned itemCatalog and itemId.
        - Do not stop after returning only the product code, item ID or description.
        - The user's original base-price request is complete only after the price results have been
          retrieved and presented.
        - If jde_search_items returns multiple products, do NOT select the first result
          automatically. Show the matching products and ask the user which one they mean.
        - If the user provides a SINGLE value, it may be an itemCatalog even when it is numeric.
          Always try that value as itemCatalog FIRST.
        - If querying by itemCatalog returns no matching product or no prices, retry using the same
          value as itemId only when the value is a valid numeric itemId.
        - Do not call jde_search_items merely because a single product reference contains letters.
          A one-word alphanumeric value may be a valid itemCatalog.
        - Never invent, transform or partially match a product code.

        IDENTIFIER SEARCH ORDER:
        1. Use itemCatalog first.
        2. If itemCatalog produces no result, use itemId as fallback when a valid numeric itemId
           is available.
        3. When jde_search_items resolved the product and returned both identifiers, send both,
           keeping itemCatalog as the primary business identifier.
        4. Do not repeatedly retry the same identifier in a loop.

        WHEN TO USE:
        - The user asks for the base, standard, catalog or list price of a product.
        - The request is not associated with a specific customer.
        - A product code has been provided or has already been resolved using jde_search_items.

        WHEN NOT TO USE:
        - The user asks for a customer-specific price, negotiated price, discount or stock
          availability. Use jde_get_item_price instead.
        - The user provides only a multi-word product description and the product has not yet been
          resolved. Call jde_search_items first.
        - The user has not provided enough information to identify the product.

        INPUT:
        - itemCatalog:
          JDE product catalog number/code.
          This is the preferred identifier and may be numeric, alphabetic or alphanumeric.
          Example values: "210", "BIKE-RED", "ABC123".

        - itemId:
          Internal numeric JDE item identifier.
          Use it as a fallback when itemCatalog does not return results, or include it when another
          tool has already returned it.
          Example: 60011.

        - At least ONE of itemCatalog or itemId is required.
        - When both identifiers have been returned by jde_search_items, send both.
        - For a user-provided single product code, try itemCatalog before itemId.

        - businessUnit:
          Optional JDE business unit or warehouse code.
          Example: "10".
          If provided, return prices for that warehouse.
          If omitted, return prices for all warehouses/business units available for the product.

        - unitOfMeasureCode:
          Optional unit-of-measure code.
          Example: "EA".
          If provided, return prices for that unit of measure.
          If omitted, return prices for all available units of measure.

        - currencyCode:
          Optional currency code.
          Example: "USD".
          If omitted, JDE uses the default currency returned by the pricing configuration.
          Do not assume or invent a currency.

        - Never invent itemCatalog, itemId, businessUnit, unitOfMeasureCode or currencyCode.
        - Only use values explicitly provided by the user or returned by another tool.

        OUTPUT (structured JSON, see outputSchema):
        - status "IN_PROGRESS":
          The request is still running against JDE.
          This is not an error.
          Call jde_get_item_list_price again using the exact same arguments after
          pollAfterSeconds.
          Do not report the operation as failed to the user.

        - status "OK":
          prices[] contains the available prices.
          Depending on the filters provided, it may contain:
          - one or more warehouses when businessUnit was omitted;
          - one or more units of measure when unitOfMeasureCode was omitted;
          - the default currency when currencyCode was omitted.

          Each result may include:
          - businessUnit
          - itemId
          - itemCatalog
          - itemDescription
          - priceList
          - currencyCode
          - unitOfMeasureCode
          - dateEffective
          - dateExpiration

        - status "FAILED" or "CANCELLED":
          The message explains what happened.
          Retry only when appropriate and avoid infinite retries.

        - status "INVALID_REQUEST":
          Neither itemCatalog nor itemId was supplied.

        IMPORTANT FOR THE ASSISTANT:
        - Always tell the user which product code was used.
        - Prefer displaying itemCatalog as the user-facing product code.
        - Include itemId as the internal JDE identifier when available.
        - Present prices[] as a Markdown table.
        - Include one row for every combination returned by JDE, especially when businessUnit or
          unitOfMeasureCode was omitted.
        - Recommended columns:
          Product Code | Item ID | Description | Warehouse | Unit | Price | Currency |
          Effective Date | Expiration Date
        - Always show the currency next to every price.
        - If currencyCode was omitted, explain that the displayed currency is the default currency
          returned by JDE.
        - If businessUnit was omitted, explain that prices are shown for all available warehouses.
        - If unitOfMeasureCode was omitted, explain that prices are shown for all available units
          of measure.
        - Never combine prices from different warehouses, currencies or units of measure into a
          single value.
        - If jde_search_items returns exactly one product, immediately call
          jde_get_item_list_price using the returned itemCatalog and itemId.
        - Do not stop after returning only the product code, item ID or description.
        - The user's original base-price request is complete only after the price results have been
          retrieved and presented.
        """
            ,
            generateOutputSchema = true
    )
    public ItemListPriceResult getItemListPrice(
            @McpToolParam(
                    description = "JDE item id, from jde_search_items, e.g. 60011. "
                            + "At least one of itemId / itemCatalog is required.",
                    required = false
            )
            Integer itemId,
            @McpToolParam(
                    description = "JDE item catalog number/code, e.g. '210' -- alternative identifier for "
                            + "the same item. At least one of itemId / itemCatalog is required.",
                    required = false
            )
            String itemCatalog,
            @McpToolParam(
                    description = "JDE business unit / warehouse code, e.g. '10'. Optional -- if omitted, "
                            + "returns one price per warehouse instead of a single one.",
                    required = false
            )
            String businessUnit,
            @McpToolParam(description = "Currency to express the price in, e.g. 'USD'. Optional.", required = false)
            String currencyCode,
            @McpToolParam(description = "Unit of measure code, e.g. 'EA'. Optional.", required = false)
            String unitOfMeasureCode,
            McpMeta meta,
            McpSyncServerExchange exchange
    ) {
        String correlationId = correlationIdContext.extractAndSet(meta);
        log.info("Tool 'jde_get_item_list_price' called with correlation ID: {}", correlationId);

        boolean hasItemId = itemId != null && itemId > 0;
        boolean hasItemCatalog = itemCatalog != null && !itemCatalog.isBlank();
        if (!hasItemId && !hasItemCatalog) {
            return new ItemListPriceResult(ToolStatus.INVALID_REQUEST,
                    "Please provide at least one item identifier: a valid item id (positive integer) "
                            + "or an item catalog code. If you only have the item description, look it "
                            + "up first with jde_search_items.",
                    0, List.of());
        }

        log.info("Requesting item list price for itemId {} / itemCatalog {} (businessUnit={})",
                itemId, itemCatalog, businessUnit);

        if (!asyncItemListPriceEnabled) {
            progressNotifications.send(exchange, meta, 0, null,
                    "Consultando precio de lista en JDE, puede tardar unos segundos...");
            try {
                List<ItemListPriceEntry> prices =
                        soClient.getItemListPrice(itemId, itemCatalog, businessUnit, currencyCode, unitOfMeasureCode);
                return new ItemListPriceResult(ToolStatus.OK, "", 0, prices);
            } catch (Exception e) {
                log.error("Error retrieving list price for itemId {} / itemCatalog {}", itemId, itemCatalog, e);
                return new ItemListPriceResult(ToolStatus.FAILED,
                        "An error occurred while retrieving the list price for item " + itemDescriptor(itemId, itemCatalog) + ". "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, List.of());
            }
        }

        String token = soClient.resolveSessionToken();
        String key = "item-list-price|" + itemId + "|" + itemCatalog + "|" + businessUnit + "|" + currencyCode + "|" + unitOfMeasureCode;

        LongRunningTask task = taskRegistry.getOrStart(
                key,
                Duration.ofMinutes(gatewayTimeoutMinutes + 1),
                defaultPollIntervalMs,
                Duration.ofSeconds(itemListPriceInitialWaitSeconds),
                correlationIdContext.wrapForBackgroundThread(correlationId,
                        () -> soClient.getItemListPriceWithToken(itemId, itemCatalog, businessUnit, currencyCode, unitOfMeasureCode, token)));

        return switch (task.status()) {
            case WORKING, INPUT_REQUIRED -> new ItemListPriceResult(
                    ToolStatus.IN_PROGRESS,
                    "The list price query for item " + itemDescriptor(itemId, itemCatalog) + " is still in "
                            + "progress (this can take a while against a busy JDE environment). Call "
                            + "jde_get_item_list_price again with the exact same arguments after "
                            + "pollAfterSeconds to get the results -- this is not an error.",
                    (int) ((task.pollIntervalMs() != null ? task.pollIntervalMs() : defaultPollIntervalMs) / 1000),
                    List.of());
            case COMPLETED -> {
                @SuppressWarnings("unchecked")
                List<ItemListPriceEntry> prices = (List<ItemListPriceEntry>) task.result();
                yield new ItemListPriceResult(ToolStatus.OK, "", 0, prices);
            }
            case FAILED -> {
                log.error("Error retrieving list price for itemId {} / itemCatalog {}: {}",
                        itemId, itemCatalog, task.error());
                yield new ItemListPriceResult(ToolStatus.FAILED,
                        "An error occurred while retrieving the list price for item " + itemDescriptor(itemId, itemCatalog) + ". "
                                + "Technical details have been logged in the MCP server. "
                                + "Ask the user to try again later or contact support.",
                        0, List.of());
            }
            case CANCELLED -> new ItemListPriceResult(ToolStatus.CANCELLED,
                    "The query was cancelled. Call jde_get_item_list_price again to retry.", 0, List.of());
        };
    }

    private static String itemDescriptor(Integer itemId, String itemCatalog) {
        if (itemId != null && itemCatalog != null && !itemCatalog.isBlank()) {
            return itemId + " (catalog " + itemCatalog + ")";
        }
        if (itemId != null) {
            return String.valueOf(itemId);
        }
        return "catalog " + itemCatalog;
    }
}
