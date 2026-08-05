package com.atina.jdeMCPServer.cart.tools;

import com.atina.jdeMCPServer.cart.model.CartClearResult;
import com.atina.jdeMCPServer.cart.model.CartCreateResult;
import com.atina.jdeMCPServer.cart.model.CartErrorCodes;
import com.atina.jdeMCPServer.cart.model.CartLineResult;
import com.atina.jdeMCPServer.cart.model.CartResult;
import com.atina.jdeMCPServer.cart.model.CartSubmitResult;
import com.atina.jdeMCPServer.cart.model.CartValidationResult;
import com.atina.jdeMCPServer.cart.model.CreatedOrderRef;
import com.atina.jdeMCPServer.cart.model.SalesCart;
import com.atina.jdeMCPServer.cart.model.SalesCartView;
import com.atina.jdeMCPServer.cart.services.CartOperationException;
import com.atina.jdeMCPServer.cart.services.CartValidationOutcome;
import com.atina.jdeMCPServer.cart.services.CartVersionConflictException;
import com.atina.jdeMCPServer.cart.services.SalesCartService;
import com.atina.jdeMCPServer.cart.services.SubmissionPreparation;
import com.atina.jdeMCPServer.mcp.CorrelationIdContext;
import com.atina.jdeMCPServer.mcp.McpProgressNotifications;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTask;
import com.atina.jdeMCPServer.mcp.tasks.LongRunningTaskRegistry;
import com.atina.jdeMCPServer.salesorder.model.CreateSalesOrderResponse;
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
import java.util.Set;

/**
 * Tools MCP del carrito de compras en memoria (un único carrito activo por
 * sesión MCP, ver com.atina.jdeMCPServer.cart.services.SalesCartService). Las
 * tools no reciben sessionId/userId/tenantId como parámetro -- se resuelven
 * server-side (CartOwnerResolver, vía SalesCartService). No contienen la
 * lógica de negocio del carrito directamente: solo validan la forma de los
 * parámetros de entrada y traducen CartOperationException a los *Result
 * estructurados (status/errorCode/message + SalesCartView).
 */
@Component
public class JdeSalesCartTools {

    private static final Logger log = LoggerFactory.getLogger(JdeSalesCartTools.class);

    /**
     * errorCode que representan un error de uso/estado del carrito (mapean a
     * ToolStatus.INVALID_REQUEST) en vez de una falla técnica/de backend
     * (ToolStatus.FAILED).
     */
    private static final Set<String> INVALID_REQUEST_CODES = Set.of(
            CartErrorCodes.CART_NOT_FOUND, CartErrorCodes.CART_ACCESS_DENIED, CartErrorCodes.CART_EXPIRED,
            CartErrorCodes.CART_NOT_EDITABLE, CartErrorCodes.CART_EMPTY, CartErrorCodes.CART_ALREADY_EXISTS,
            CartErrorCodes.CART_LIMIT_EXCEEDED, CartErrorCodes.CART_LINE_NOT_FOUND,
            CartErrorCodes.CART_VERSION_CONFLICT, CartErrorCodes.CUSTOMER_MISMATCH,
            CartErrorCodes.CURRENCY_NOT_RESOLVED);

    private final SalesCartService cartService;
    private final JdeSalesOrderClient soClient;
    private final CorrelationIdContext correlationIdContext;
    private final McpProgressNotifications progressNotifications;
    private final LongRunningTaskRegistry taskRegistry;
    private final boolean asyncSubmitEnabled;
    private final long submitInitialWaitSeconds;
    private final long gatewayTimeoutMinutes;
    private final long defaultPollIntervalMs;

    public JdeSalesCartTools(
            SalesCartService cartService,
            JdeSalesOrderClient soClient,
            CorrelationIdContext correlationIdContext,
            McpProgressNotifications progressNotifications,
            LongRunningTaskRegistry taskRegistry,
            @Value("${jde.sales-order.submit.async.enabled:true}") boolean asyncSubmitEnabled,
            @Value("${jde.sales-order.submit.async.initial-wait-seconds:8}") long submitInitialWaitSeconds,
            @Value("${jde.atina.gateway.timeout-minutes:10}") long gatewayTimeoutMinutes,
            @Value("${jde.mcp.tasks.default-poll-interval-ms:5000}") long defaultPollIntervalMs) {
        this.cartService = cartService;
        this.soClient = soClient;
        this.correlationIdContext = correlationIdContext;
        this.progressNotifications = progressNotifications;
        this.taskRegistry = taskRegistry;
        this.asyncSubmitEnabled = asyncSubmitEnabled;
        this.submitInitialWaitSeconds = submitInitialWaitSeconds;
        this.gatewayTimeoutMinutes = gatewayTimeoutMinutes;
        this.defaultPollIntervalMs = defaultPollIntervalMs;
    }

    private static ToolStatus statusFor(String errorCode) {
        return INVALID_REQUEST_CODES.contains(errorCode) ? ToolStatus.INVALID_REQUEST : ToolStatus.FAILED;
    }

    // =========================================================================
    // Tool 1: Crear el carrito activo de la sesión
    // =========================================================================
    @McpTool(
            name = "jde_create_current_sales_cart",
            description = """
        Start a new shopping cart for the current session, to progressively build a sales order
        before creating it in JDE.

        PURPOSE:
        - Creates a single active shopping cart tied to the current session, fixing the customer
          (and optionally the warehouse/currency) before any product line is added.
        - This is the recommended first step of the cart workflow, so every line added afterwards
          is validated against a known customer.

        WHEN TO USE:
        - At the start of a conversational order-building flow ("I want to place an order for
          customer X").

        WHEN NOT TO USE:
        - If a cart is already active for this session and the user only wants to add more items --
          call jde_add_item_to_current_sales_cart directly instead.
        - jde_add_item_to_current_sales_cart can also auto-create a cart if entityId is provided and
          none exists yet, so calling this tool first is a recommendation, not a strict requirement.

        REQUIRED CUSTOMER IDENTIFICATION:
        - entityId is the customer's JDE Address Book Number.
        - If the user provides only a customer name, call jde_lookup_customer_by_name first.
        - If jde_lookup_customer_by_name returns exactly one customer, continue automatically.
        - If multiple customers are returned, do not select the first one -- ask the user which one
          they mean.
        - Never invent an entityId.

        BEHAVIOR IF A CART ALREADY EXISTS:
        - This tool does NOT silently overwrite an existing active cart.
        - If one is already active (any status other than a previously completed/cancelled/expired
          one), it returns errorCode "CART_ALREADY_EXISTS" together with the current cart, so the
          model can show it to the user and suggest jde_get_current_sales_cart or
          jde_clear_current_sales_cart.

        INPUT:
        - entityId: customer JDE Address Book Number. Required.
        - businessUnit: optional JDE warehouse/business unit for this order, e.g. "30". Every line
          can also specify its own businessUnit later; if omitted here, it must be provided per line.
        - currencyCode: optional transaction currency, e.g. "USD". If omitted, it is resolved
          automatically from the customer's own transaction currency (jde_get_customer_detail).

        OUTPUT (structured JSON, see outputSchema):
        - status "OK": the cart was created. cart.status is "OPEN".
        - status "INVALID_REQUEST": errorCode "CART_ALREADY_EXISTS" plus the current cart, or a
          missing/invalid entityId.
        - status "FAILED": a technical error occurred resolving the customer or creating the cart.

        IMPORTANT FOR THE ASSISTANT:
        - Never invent entityId, businessUnit or currencyCode.
        - After creating the cart, continue with jde_add_item_to_current_sales_cart for each product
          the user wants to order.
        """,
            generateOutputSchema = true
    )
    public CartCreateResult createCart(
            @McpToolParam(description = "Customer JDE Address Book Number. Resolve it with " +
                    "jde_lookup_customer_by_name when only the customer name is known. Required.")
            Integer entityId,

            @McpToolParam(description = "Optional JDE business unit/warehouse code for this order, e.g. '30'.",
                    required = false)
            String businessUnit,

            @McpToolParam(description = "Optional transaction currency code, e.g. 'USD'. If omitted, resolved " +
                    "automatically from the customer's currency via jde_get_customer_detail.", required = false)
            String currencyCode,

            McpMeta meta, McpSyncServerExchange exchange
    ) {
        log.info("Tool 'jde_create_current_sales_cart' called with correlation ID: {}",
                correlationIdContext.extractAndSet(meta));

        if (entityId == null || entityId <= 0) {
            return new CartCreateResult(ToolStatus.INVALID_REQUEST, CartErrorCodes.NONE,
                    "Please provide a valid customer AB Number (positive integer). If you only have the "
                            + "customer name, look it up first with jde_lookup_customer_by_name.",
                    SalesCartView.empty());
        }

        try {
            SalesCart cart = cartService.createCart(entityId, businessUnit, currencyCode);
            return new CartCreateResult(ToolStatus.OK, CartErrorCodes.NONE, "Cart created.", SalesCartView.from(cart));
        } catch (CartOperationException e) {
            return new CartCreateResult(statusFor(e.errorCode()), e.errorCode(), e.getMessage(), SalesCartView.empty());
        } catch (Exception e) {
            log.error("Error creating sales cart for entityId {}", entityId, e);
            return new CartCreateResult(ToolStatus.FAILED, CartErrorCodes.NONE,
                    "An error occurred while creating the shopping cart. Technical details have been logged "
                            + "in the MCP server.", SalesCartView.empty());
        }
    }

    // =========================================================================
    // Tool 2: Agregar un producto al carrito
    // =========================================================================
    @McpTool(
            name = "jde_add_item_to_current_sales_cart",
            description = """
        Add a product line to the active shopping cart of the current session, pricing it
        specifically for the cart's customer.

        PURPOSE:
        - Resolves the customer-specific price for a product (same pricing rules as
          jde_get_item_price -- NEVER the list price) and adds it as a new line to the cart.
        - If no cart is active yet for this session and entityId is provided, the cart is
          auto-created with that customer before the line is added.

        REQUIRED PRODUCT IDENTIFICATION:
        - A product code must be available before calling this tool.
        - The product may be identified by:
          - itemCatalog: the external/catalog product code (preferred identifier).
          - itemId: the internal numeric JDE item identifier (fallback identifier).
        - itemCatalog may be numeric, alphabetic or alphanumeric -- never assume that a numeric
          value is necessarily an itemId.
        - If the user provides a multi-word product description, call jde_search_items first. If it
          returns exactly one product, continue automatically using itemCatalog (and itemId when
          available). If it returns multiple, show them and ask the user which one they mean.
        - Never invent, modify or partially match a product code.

        CUSTOMER:
        - entityId is required only if no cart is active yet for this session (it auto-creates one).
        - If a cart is already active, entityId is not required; if provided, it must match the
          cart's customer -- otherwise the call fails with errorCode "CUSTOMER_MISMATCH" (a cart can
          only hold lines for a single customer).

        WHEN TO USE:
        - The user wants to add a product to the order being built.

        WHEN NOT TO USE:
        - To change the quantity/unit of an existing line -- use jde_update_current_sales_cart_item.

        INPUT:
        - entityId: customer JDE Address Book Number. Required only to auto-create the cart.
        - itemCatalog / itemId: at least one required, see above.
        - description: optional human-readable product description (e.g. already known from
          jde_search_items), stored on the line for display purposes only.
        - quantity: quantity requested. Required, must be greater than zero. Never invent it.
        - unitOfMeasure: optional, e.g. "EA". Defaults to the server default if omitted.
        - businessUnit: optional JDE warehouse/business unit for this line. If omitted, uses the
          cart's business unit.
        - currencyCode: optional. If omitted, uses the cart's currency.
        - returnAvailability: optional, "Y" to also check stock availability for this line, "N"
          (default) for price only.

        OUTPUT (structured JSON, see outputSchema):
        - status "OK": the line was added. The response includes the full updated cart (all lines,
          version, total).
        - status "INVALID_REQUEST": see errorCode (e.g. "CART_NOT_FOUND", "CUSTOMER_MISMATCH",
          "CART_NOT_EDITABLE", "CURRENCY_NOT_RESOLVED").
        - status "FAILED": a technical error occurred resolving product/price.

        IMPORTANT FOR THE ASSISTANT:
        - Never invent entityId, itemCatalog, itemId, quantity, businessUnit or currencyCode.
        - Always show the customer-specific unit price and extended price after adding a line.
        """,
            generateOutputSchema = true
    )
    public CartLineResult addItem(
            @McpToolParam(description = "Customer JDE Address Book Number. Required only if no active cart " +
                    "exists yet for this session -- in that case the cart is auto-created with this customer. " +
                    "If a cart already exists, this must match its customer or be omitted.", required = false)
            Integer entityId,

            @McpToolParam(description = "Preferred JDE catalog product code, e.g. '210', 'BIKE-RED' or " +
                    "'ABC123'. At least one of itemCatalog or itemId is required.", required = false)
            String itemCatalog,

            @McpToolParam(description = "Internal numeric JDE item identifier, e.g. 60011. Fallback when " +
                    "itemCatalog returns no result.", required = false)
            Integer itemId,

            @McpToolParam(description = "Optional human-readable product description, for display only.",
                    required = false)
            String description,

            @McpToolParam(description = "Quantity requested, e.g. 2. Required, must be greater than zero.")
            Double quantity,

            @McpToolParam(description = "Optional unit-of-measure code, e.g. 'EA'.", required = false)
            String unitOfMeasure,

            @McpToolParam(description = "Optional JDE business unit/warehouse code for this line. If " +
                    "omitted, uses the cart's business unit.", required = false)
            String businessUnit,

            @McpToolParam(description = "Optional currency code. If omitted, uses the cart's currency.",
                    required = false)
            String currencyCode,

            @McpToolParam(description = "Use 'Y' to also check stock availability for this line. Optional, " +
                    "defaults to 'N'.", required = false)
            String returnAvailability,

            McpMeta meta, McpSyncServerExchange exchange
    ) {
        log.info("Tool 'jde_add_item_to_current_sales_cart' called with correlation ID: {}",
                correlationIdContext.extractAndSet(meta));

        boolean hasItemId = itemId != null && itemId > 0;
        boolean hasItemCatalog = itemCatalog != null && !itemCatalog.isBlank();
        if (!hasItemId && !hasItemCatalog) {
            return new CartLineResult(ToolStatus.INVALID_REQUEST, CartErrorCodes.NONE,
                    "Please provide at least one item identifier: itemCatalog (preferred) or itemId. If you "
                            + "only have the item description, look it up first with jde_search_items.",
                    SalesCartView.empty());
        }
        if (quantity == null || quantity <= 0) {
            return new CartLineResult(ToolStatus.INVALID_REQUEST, CartErrorCodes.NONE,
                    "Please provide a valid quantity (greater than zero).", SalesCartView.empty());
        }

        try {
            SalesCart cart = cartService.addLine(entityId, itemId, itemCatalog, description, quantity,
                    unitOfMeasure, businessUnit, currencyCode, returnAvailability);
            return new CartLineResult(ToolStatus.OK, CartErrorCodes.NONE, "Item added to cart.",
                    SalesCartView.from(cart));
        } catch (CartOperationException e) {
            return new CartLineResult(statusFor(e.errorCode()), e.errorCode(), e.getMessage(), SalesCartView.empty());
        } catch (Exception e) {
            log.error("Error adding item to sales cart itemId={} itemCatalog={}", itemId, itemCatalog, e);
            return new CartLineResult(ToolStatus.FAILED, CartErrorCodes.NONE,
                    "An error occurred while adding the item to the cart. Technical details have been logged "
                            + "in the MCP server.", SalesCartView.empty());
        }
    }

    // =========================================================================
    // Tool 3: Actualizar cantidad/UM de una línea existente
    // =========================================================================
    @McpTool(
            name = "jde_update_current_sales_cart_item",
            description = """
        Change the quantity and/or unit of measure of an existing line in the active shopping cart,
        recalculating its customer-specific price.

        WHEN TO USE:
        - The user wants to change how many units of an already-added product they want.

        WHEN NOT TO USE:
        - To change the product itself -- remove the line (jde_remove_current_sales_cart_item) and
          add a new one (jde_add_item_to_current_sales_cart) instead.

        INPUT:
        - lineId: id of the cart line to update, exactly as returned by
          jde_add_item_to_current_sales_cart or jde_get_current_sales_cart. Required. Never invent it.
        - quantity: new quantity, e.g. 3. Optional -- if omitted, quantity is unchanged.
        - unitOfMeasure: new unit of measure. Optional -- if omitted, unchanged.
        - At least one of quantity or unitOfMeasure is required.

        OUTPUT (structured JSON, see outputSchema): same shape as jde_add_item_to_current_sales_cart --
        the full updated cart. errorCode "CART_LINE_NOT_FOUND" if lineId does not exist.
        """,
            generateOutputSchema = true
    )
    public CartLineResult updateItem(
            @McpToolParam(description = "lineId of the cart line to update, as returned by " +
                    "jde_add_item_to_current_sales_cart or jde_get_current_sales_cart. Required.")
            String lineId,

            @McpToolParam(description = "New quantity, e.g. 3. Optional -- if omitted, quantity is unchanged.",
                    required = false)
            Double quantity,

            @McpToolParam(description = "New unit of measure. Optional -- if omitted, unchanged.",
                    required = false)
            String unitOfMeasure,

            McpMeta meta, McpSyncServerExchange exchange
    ) {
        log.info("Tool 'jde_update_current_sales_cart_item' called with correlation ID: {}",
                correlationIdContext.extractAndSet(meta));

        if (lineId == null || lineId.isBlank()) {
            return new CartLineResult(ToolStatus.INVALID_REQUEST, CartErrorCodes.NONE,
                    "Please provide the lineId of the cart line to update.", SalesCartView.empty());
        }
        if ((quantity == null) && (unitOfMeasure == null || unitOfMeasure.isBlank())) {
            return new CartLineResult(ToolStatus.INVALID_REQUEST, CartErrorCodes.NONE,
                    "Please provide a new quantity and/or unit of measure to update.", SalesCartView.empty());
        }
        if (quantity != null && quantity <= 0) {
            return new CartLineResult(ToolStatus.INVALID_REQUEST, CartErrorCodes.NONE,
                    "Please provide a valid quantity (greater than zero).", SalesCartView.empty());
        }

        try {
            SalesCart cart = cartService.updateLine(lineId, quantity, unitOfMeasure);
            return new CartLineResult(ToolStatus.OK, CartErrorCodes.NONE, "Cart line updated.",
                    SalesCartView.from(cart));
        } catch (CartOperationException e) {
            return new CartLineResult(statusFor(e.errorCode()), e.errorCode(), e.getMessage(), SalesCartView.empty());
        } catch (Exception e) {
            log.error("Error updating sales cart line {}", lineId, e);
            return new CartLineResult(ToolStatus.FAILED, CartErrorCodes.NONE,
                    "An error occurred while updating the cart line. Technical details have been logged in "
                            + "the MCP server.", SalesCartView.empty());
        }
    }

    // =========================================================================
    // Tool 4: Eliminar una línea del carrito
    // =========================================================================
    @McpTool(
            name = "jde_remove_current_sales_cart_item",
            description = """
        Remove a line from the active shopping cart.

        INPUT:
        - lineId: id of the cart line to remove, exactly as returned by
          jde_add_item_to_current_sales_cart or jde_get_current_sales_cart. Required. Never invent it.

        OUTPUT (structured JSON, see outputSchema): the full updated cart without that line.
        errorCode "CART_LINE_NOT_FOUND" if lineId does not exist -- calling this tool again with an
        already-removed lineId is reported as an error, not treated as a silent no-op.
        """,
            generateOutputSchema = true
    )
    public CartLineResult removeItem(
            @McpToolParam(description = "lineId of the cart line to remove. Required.")
            String lineId,
            McpMeta meta, McpSyncServerExchange exchange
    ) {
        log.info("Tool 'jde_remove_current_sales_cart_item' called with correlation ID: {}",
                correlationIdContext.extractAndSet(meta));

        if (lineId == null || lineId.isBlank()) {
            return new CartLineResult(ToolStatus.INVALID_REQUEST, CartErrorCodes.NONE,
                    "Please provide the lineId of the cart line to remove.", SalesCartView.empty());
        }

        try {
            SalesCart cart = cartService.removeLine(lineId);
            return new CartLineResult(ToolStatus.OK, CartErrorCodes.NONE, "Cart line removed.",
                    SalesCartView.from(cart));
        } catch (CartOperationException e) {
            return new CartLineResult(statusFor(e.errorCode()), e.errorCode(), e.getMessage(), SalesCartView.empty());
        } catch (Exception e) {
            log.error("Error removing sales cart line {}", lineId, e);
            return new CartLineResult(ToolStatus.FAILED, CartErrorCodes.NONE,
                    "An error occurred while removing the cart line. Technical details have been logged in "
                            + "the MCP server.", SalesCartView.empty());
        }
    }

    // =========================================================================
    // Tool 5: Consultar el carrito activo
    // =========================================================================
    @McpTool(
            name = "jde_get_current_sales_cart",
            description = """
        Read-only lookup of the active shopping cart for the current session. Does NOT re-check
        prices, availability or the customer against JDE -- use jde_validate_current_sales_cart for
        that before confirming an order.

        WHEN TO USE:
        - The user asks what is currently in the cart.
        - Before showing a summary or asking for confirmation, to display the latest known state.

        OUTPUT (structured JSON, see outputSchema):
        - status "OK" with errorCode "CART_NOT_FOUND" and an empty cart: no active cart exists for
          this session. This is a normal, expected state, NOT an error -- suggest
          jde_create_current_sales_cart to the user.
        - status "OK" with errorCode "" (empty): the cart is returned.
        """,
            generateOutputSchema = true
    )
    public CartResult getCart(McpMeta meta, McpSyncServerExchange exchange) {
        log.info("Tool 'jde_get_current_sales_cart' called with correlation ID: {}",
                correlationIdContext.extractAndSet(meta));

        try {
            SalesCart cart = cartService.getCart();
            return new CartResult(ToolStatus.OK, CartErrorCodes.NONE, "", SalesCartView.from(cart));
        } catch (CartOperationException e) {
            if (CartErrorCodes.CART_NOT_FOUND.equals(e.errorCode())) {
                return new CartResult(ToolStatus.OK, e.errorCode(), e.getMessage(), SalesCartView.empty());
            }
            return new CartResult(statusFor(e.errorCode()), e.errorCode(), e.getMessage(), SalesCartView.empty());
        } catch (Exception e) {
            log.error("Error retrieving sales cart", e);
            return new CartResult(ToolStatus.FAILED, CartErrorCodes.NONE,
                    "An error occurred while retrieving the cart. Technical details have been logged in the "
                            + "MCP server.", SalesCartView.empty());
        }
    }

    // =========================================================================
    // Tool 6: Revalidar el carrito antes de confirmar
    // =========================================================================
    @McpTool(
            name = "jde_validate_current_sales_cart",
            description = """
        Re-check every line of the active shopping cart against JDE (current customer-specific
        price, and stock availability when enabled server-side) before asking the user to confirm
        the order. This tool must be called before jde_submit_current_sales_cart whenever the cart
        has not just been freshly built, since prices/availability can change between when a line
        was added and when the user is ready to confirm.

        WHEN TO USE:
        - Right before presenting the final order summary to the user for confirmation.

        OUTPUT (structured JSON, see outputSchema):
        - status "OK", requiresReconfirmation=false: nothing changed. cart.status is now
          "READY_FOR_CONFIRMATION" -- safe to present the summary and ask for explicit confirmation.
        - status "OK", requiresReconfirmation=true: at least one price or availability changed since
          the line was added/last validated. changes[] lists every field that changed (lineId, field,
          previousValue, currentValue). cart.status is back to "OPEN". Show the updated values to the
          user and ask them to confirm again before calling jde_submit_current_sales_cart.
        - status "INVALID_REQUEST": errorCode "CART_NOT_FOUND" / "CART_EMPTY" / "CART_NOT_EDITABLE".

        IMPORTANT FOR THE ASSISTANT:
        - When requiresReconfirmation is true, do NOT proceed to jde_submit_current_sales_cart without
          the user explicitly re-confirming the updated order.
        """,
            generateOutputSchema = true
    )
    public CartValidationResult validateCart(McpMeta meta, McpSyncServerExchange exchange) {
        log.info("Tool 'jde_validate_current_sales_cart' called with correlation ID: {}",
                correlationIdContext.extractAndSet(meta));

        try {
            CartValidationOutcome outcome = cartService.validateCart();
            String message = outcome.requiresReconfirmation()
                    ? "Prices or availability changed since the cart was last validated. Review the changes "
                            + "and ask the user to confirm again."
                    : "The cart is up to date and ready for confirmation.";
            return new CartValidationResult(ToolStatus.OK, CartErrorCodes.NONE, message,
                    outcome.requiresReconfirmation(), outcome.changes(), SalesCartView.from(outcome.cart()));
        } catch (CartOperationException e) {
            return new CartValidationResult(statusFor(e.errorCode()), e.errorCode(), e.getMessage(),
                    false, List.of(), SalesCartView.empty());
        } catch (Exception e) {
            log.error("Error validating sales cart", e);
            return new CartValidationResult(ToolStatus.FAILED, CartErrorCodes.NONE,
                    "An error occurred while validating the cart. Technical details have been logged in the "
                            + "MCP server.", false, List.of(), SalesCartView.empty());
        }
    }

    // =========================================================================
    // Tool 7: Vaciar el carrito activo
    // =========================================================================
    @McpTool(
            name = "jde_clear_current_sales_cart",
            description = """
        Discard the active shopping cart for the current session.

        IMPORTANT FOR THE ASSISTANT:
        - This is a destructive operation. Only call it after the user has explicitly asked to
          clear/cancel/start over the cart -- do not call it merely because the user is unsure or
          asked a question.

        BEHAVIOR IF THE CART ALREADY HAS A CREATED ORDER:
        - If the cart's status is already "ORDER_CREATED" (the sales order was already submitted to
          JDE), this tool does NOT delete it -- the order data remains available in the response for
          traceability. Call jde_create_current_sales_cart to start a new cart afterwards.

        INPUT:
        - confirm: must be true. Required to avoid accidental data loss.

        OUTPUT (structured JSON, see outputSchema): the cart as it was right before clearing (or as it
        remains, if it already had a created order).
        """,
            generateOutputSchema = true
    )
    public CartClearResult clearCart(
            @McpToolParam(description = "Set to true to confirm clearing the cart. Required.")
            Boolean confirm,
            McpMeta meta, McpSyncServerExchange exchange
    ) {
        log.info("Tool 'jde_clear_current_sales_cart' called with correlation ID: {}",
                correlationIdContext.extractAndSet(meta));

        if (confirm == null || !confirm) {
            return new CartClearResult(ToolStatus.INVALID_REQUEST, CartErrorCodes.NONE,
                    "Please confirm explicitly (confirm=true) before clearing the cart.", SalesCartView.empty());
        }

        try {
            SalesCart preserved = cartService.clearCart();
            if (preserved != null) {
                return new CartClearResult(ToolStatus.OK, CartErrorCodes.NONE,
                        "The order for this cart was already created in JDE; the cart is preserved for "
                                + "traceability instead of being deleted. Call jde_create_current_sales_cart "
                                + "to start a new one.", SalesCartView.from(preserved));
            }
            return new CartClearResult(ToolStatus.OK, CartErrorCodes.NONE, "Cart cleared.", SalesCartView.empty());
        } catch (CartOperationException e) {
            return new CartClearResult(statusFor(e.errorCode()), e.errorCode(), e.getMessage(), SalesCartView.empty());
        } catch (Exception e) {
            log.error("Error clearing sales cart", e);
            return new CartClearResult(ToolStatus.FAILED, CartErrorCodes.NONE,
                    "An error occurred while clearing the cart. Technical details have been logged in the "
                            + "MCP server.", SalesCartView.empty());
        }
    }

    // =========================================================================
    // Tool 8: Confirmar y crear el pedido de venta en JDE
    // =========================================================================
    @McpTool(
            name = "jde_submit_current_sales_cart",
            description = """
        Create the sales order in JDE from the active shopping cart. This is the ONLY tool in this
        set that writes an order into JDE -- all other cart tools only affect an in-memory draft.

        STRONG CONFIRMATION RULE:
        - This tool must be called ONLY after the full order summary (customer, every line with its
          price, currency, total) has been shown to the user AND the user has explicitly confirmed
          placing the order.
        - Adding an item, viewing the cart, or validating the cart does NOT constitute confirmation.
        - An ambiguous response must NOT be interpreted as authorization.
        - Valid confirmations: "Confirm the order", "Yes, place the order", "Create the order with
          those details".
        - NOT valid confirmations: "OK", "Show me the total", "Validate it", "Add another item".
        - confirm must be true. If the user has not clearly confirmed, do not call this tool.

        REQUIRED SEQUENCE BEFORE CALLING THIS TOOL:
        1. The cart must have at least one line (built via jde_add_item_to_current_sales_cart).
        2. jde_validate_current_sales_cart must have been called and returned requiresReconfirmation
           false, showing the user the up-to-date summary.
        3. The user must have explicitly confirmed the order as described above.
        4. expectedCartVersion must be the cart's version exactly as last seen by the user (from
           jde_validate_current_sales_cart or jde_get_current_sales_cart).

        OPTIMISTIC CONCURRENCY:
        - expectedCartVersion is checked against the cart's current version before creating anything.
        - If they don't match, the call fails with errorCode "CART_VERSION_CONFLICT" and
          currentCartVersion in the response -- the cart changed after the user last saw it. Show the
          user the current state again (jde_get_current_sales_cart) and ask them to confirm again.

        DUPLICATE ORDER PROTECTION (LIMITED -- read carefully):
        - If this cart already has a created order (a previous successful call, or a retry after a
          slow response), this tool returns the SAME order (recoveredFromExistingOrder=true) instead
          of creating a new one, as long as the in-memory cart still exists.
        - This protection does NOT survive an MCP server restart, and does NOT cover two different
          sessions accidentally placing the same order. There is currently no way to look up an
          existing JDE sales order by external reference, so a retry after a server restart may
          create a duplicate order in JDE. Treat this tool with the same care as any other real,
          irreversible write operation.

        OUTPUT (structured JSON, see outputSchema):
        - status "IN_PROGRESS": the order is being created in JDE. This is not an error -- call this
          tool again with the EXACT SAME expectedCartVersion and confirm=true after pollAfterSeconds.
        - status "OK": the order was created (or already existed for this cart). company, orderNumber,
          orderType and externalReference identify it. recoveredFromExistingOrder indicates whether
          it was created by this call or recovered from a previous one.
        - status "INVALID_REQUEST": see errorCode (e.g. "CART_VERSION_CONFLICT", "CART_EMPTY",
          "CURRENCY_NOT_RESOLVED", "CREDIT_LIMIT_EXCEEDED").
        - status "FAILED": errorCode "ORDER_SUBMISSION_FAILED" -- a technical error occurred creating
          the order. The cart was kept as-is so the user can retry.

        IMPORTANT FOR THE ASSISTANT:
        - Never call this tool speculatively "to see what happens".
        - Never invent expectedCartVersion -- it must come from a value the tool itself returned.
        - After a successful order, clearly tell the user the JDE order number.
        """,
            generateOutputSchema = true
    )
    public CartSubmitResult submitCart(
            @McpToolParam(description = "Current cart version, exactly as returned by the last " +
                    "jde_get_current_sales_cart / jde_validate_current_sales_cart call. Required -- used " +
                    "for optimistic concurrency control. Never invent it.")
            Long expectedCartVersion,

            @McpToolParam(description = "Must be true. Represents the user's EXPLICIT confirmation to " +
                    "place the order. Adding, viewing or validating the cart does NOT count as " +
                    "confirmation -- only call this tool with confirm=true after the user has explicitly " +
                    "and unambiguously confirmed.")
            Boolean confirm,

            McpMeta meta, McpSyncServerExchange exchange
    ) {
        String correlationId = correlationIdContext.extractAndSet(meta);
        log.info("Tool 'jde_submit_current_sales_cart' called with correlation ID: {}", correlationId);

        if (expectedCartVersion == null) {
            return new CartSubmitResult(ToolStatus.INVALID_REQUEST, CartErrorCodes.NONE,
                    "Please provide expectedCartVersion, exactly as last returned by "
                            + "jde_get_current_sales_cart or jde_validate_current_sales_cart.",
                    0, "", "", "", "", false, 0L, SalesCartView.empty());
        }
        if (confirm == null || !confirm) {
            return new CartSubmitResult(ToolStatus.INVALID_REQUEST, CartErrorCodes.NONE,
                    "The order was not confirmed. Ask the user to explicitly confirm placing the order "
                            + "before calling this tool again with confirm=true.",
                    0, "", "", "", "", false, 0L, SalesCartView.empty());
        }

        try {
            SubmissionPreparation prep = cartService.prepareSubmission(expectedCartVersion);

            if (prep.recovered()) {
                return okResult(prep.cart(), prep.recoveredOrder(), "Sales order already created for this cart.");
            }

            String sessionId = prep.cart().sessionId();
            progressNotifications.send(exchange, meta, 0, null,
                    "Submitting the sales order to JDE, this can take a few seconds...");

            if (!asyncSubmitEnabled) {
                try {
                    CreateSalesOrderResponse response = soClient.createSalesOrder(prep.request());
                    SalesCart finalCart = cartService.finalizeSubmission(sessionId, response, prep.externalReference());
                    return okResult(finalCart, finalCart.createdOrder(), buildSuccessMessage(prep.warning()));
                } catch (Exception e) {
                    log.error("Error submitting sales order for cart {}", prep.cart().cartId(), e);
                    cartService.failSubmission(sessionId);
                    return pendingOrFailedResult(ToolStatus.FAILED, CartErrorCodes.ORDER_SUBMISSION_FAILED,
                            "An error occurred while creating the sales order in JDE. The cart was kept so "
                                    + "you can retry. Technical details have been logged in the MCP server.",
                            0, prep.externalReference(), prep.cart());
                }
            }

            String token = soClient.resolveSessionToken();
            String key = "submit-cart|" + sessionId + "|" + prep.externalReference();

            LongRunningTask task = taskRegistry.getOrStart(
                    key,
                    Duration.ofMinutes(gatewayTimeoutMinutes + 1),
                    defaultPollIntervalMs,
                    Duration.ofSeconds(submitInitialWaitSeconds),
                    correlationIdContext.wrapForBackgroundThread(correlationId,
                            () -> soClient.createSalesOrderWithToken(prep.request(), token)));

            return switch (task.status()) {
                case WORKING, INPUT_REQUIRED -> pendingOrFailedResult(ToolStatus.IN_PROGRESS, CartErrorCodes.NONE,
                        "The order is being submitted to JDE. Call this tool again with the exact same "
                                + "expectedCartVersion and confirm=true after pollAfterSeconds.",
                        (int) ((task.pollIntervalMs() != null ? task.pollIntervalMs() : defaultPollIntervalMs) / 1000),
                        prep.externalReference(), prep.cart());
                case COMPLETED -> {
                    CreateSalesOrderResponse response = (CreateSalesOrderResponse) task.result();
                    SalesCart finalCart = cartService.finalizeSubmission(sessionId, response, prep.externalReference());
                    yield okResult(finalCart, finalCart.createdOrder(), buildSuccessMessage(prep.warning()));
                }
                case FAILED -> {
                    log.error("Sales order submission failed for cart {}: {}", prep.cart().cartId(), task.error());
                    cartService.failSubmission(sessionId);
                    yield pendingOrFailedResult(ToolStatus.FAILED, CartErrorCodes.ORDER_SUBMISSION_FAILED,
                            "An error occurred while creating the sales order in JDE. The cart was kept so "
                                    + "you can retry. Technical details have been logged in the MCP server.",
                            0, prep.externalReference(), prep.cart());
                }
                case CANCELLED -> {
                    cartService.failSubmission(sessionId);
                    yield pendingOrFailedResult(ToolStatus.CANCELLED, CartErrorCodes.NONE,
                            "The order submission was cancelled.", 0, prep.externalReference(), prep.cart());
                }
            };
        } catch (CartVersionConflictException e) {
            return new CartSubmitResult(statusFor(e.errorCode()), e.errorCode(), e.getMessage(), 0,
                    "", "", "", "", false, e.currentVersion(), SalesCartView.empty());
        } catch (CartOperationException e) {
            return new CartSubmitResult(statusFor(e.errorCode()), e.errorCode(), e.getMessage(), 0,
                    "", "", "", "", false, 0L, SalesCartView.empty());
        } catch (Exception e) {
            log.error("Error submitting sales cart", e);
            return new CartSubmitResult(ToolStatus.FAILED, CartErrorCodes.NONE,
                    "An error occurred while submitting the order. Technical details have been logged in "
                            + "the MCP server.", 0, "", "", "", "", false, 0L, SalesCartView.empty());
        }
    }

    private static CartSubmitResult okResult(SalesCart cart, CreatedOrderRef order, String message) {
        return new CartSubmitResult(ToolStatus.OK, CartErrorCodes.NONE, message, 0,
                order.company(), order.orderNumber(), order.orderType(), order.externalReference(),
                order.recoveredFromExistingOrder(), cart.version(), SalesCartView.from(cart));
    }

    private static CartSubmitResult pendingOrFailedResult(ToolStatus status, String errorCode, String message,
                                                            int pollAfterSeconds, String externalReference,
                                                            SalesCart cart) {
        return new CartSubmitResult(status, errorCode, message, pollAfterSeconds, "", "", "", externalReference,
                false, cart.version(), SalesCartView.from(cart));
    }

    private static String buildSuccessMessage(String warning) {
        String base = "Sales order created successfully.";
        return (warning != null && !warning.isBlank()) ? base + " " + warning : base;
    }
}
