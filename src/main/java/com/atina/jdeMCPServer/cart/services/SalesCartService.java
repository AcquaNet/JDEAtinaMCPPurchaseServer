package com.atina.jdeMCPServer.cart.services;

import com.atina.jdeMCPServer.cart.model.CartChange;
import com.atina.jdeMCPServer.cart.model.CartErrorCodes;
import com.atina.jdeMCPServer.cart.model.CartStatus;
import com.atina.jdeMCPServer.cart.model.CreatedOrderRef;
import com.atina.jdeMCPServer.cart.model.SalesCart;
import com.atina.jdeMCPServer.cart.model.SalesCartLine;
import com.atina.jdeMCPServer.salesorder.model.CreateSalesOrderRequest;
import com.atina.jdeMCPServer.salesorder.model.CreateSalesOrderResponse;
import com.atina.jdeMCPServer.salesorder.model.CustomerCreditSummary;
import com.atina.jdeMCPServer.salesorder.model.CustomerDetail;
import com.atina.jdeMCPServer.salesorder.model.PriceQuote;
import com.atina.jdeMCPServer.salesorder.model.SalesOrderLineRequest;
import com.atina.jdeMCPServer.salesorder.services.JdeSalesOrderClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orquesta el ciclo de vida del carrito de compras: resuelve sessionId/owner
 * (CartOwnerResolver), mantiene el estado en SalesCartRepository, y llama a
 * JdeSalesOrderClient para resolver cliente/producto/precio/disponibilidad --
 * las tools (JdeSalesCartTools) no contienen esta lógica directamente, solo
 * validan la forma de los parámetros de entrada y traducen
 * CartOperationException a los *Result estructurados.
 *
 * Las llamadas de red a JDE (precio, detalle de cliente) SIEMPRE se resuelven
 * ANTES de invocar SalesCartRepository.update(...), nunca dentro del propio
 * mutator: ConcurrentHashMap.compute() debe correr rápido y sin IO
 * bloqueante, igual que exige su propio Javadoc.
 */
@Service
public class SalesCartService {

    private final SalesCartRepository repository;
    private final JdeSalesOrderClient soClient;
    private final CartOwnerResolver ownerResolver;
    private final String defaultUnitOfMeasure;
    private final String defaultProcessingVersion;
    private final boolean checkAvailabilityOnAdd;
    private final boolean checkAvailabilityOnValidate;
    private final boolean checkCreditOnSubmit;
    private final boolean blockOnCreditExceeded;
    private final String salesOrderDocumentTypeCode;
    private final String salesOrderCompany;
    private final String salesOrderDocumentCompany;
    private final String salesOrderLineTypeCode;
    private final String salesOrderActionType;
    private final String salesOrderProcessingVersion;
    private final int requestedDateLeadDays;

    public SalesCartService(
            SalesCartRepository repository,
            JdeSalesOrderClient soClient,
            CartOwnerResolver ownerResolver,
            @Value("${jde.pricing.default-unit-of-measure:EA}") String defaultUnitOfMeasure,
            @Value("${jde.pricing.default-processing-version:ZJDE0001}") String defaultProcessingVersion,
            @Value("${jde.cart.check-availability-on-add:false}") boolean checkAvailabilityOnAdd,
            @Value("${jde.cart.check-availability-on-validate:true}") boolean checkAvailabilityOnValidate,
            @Value("${jde.cart.check-credit-on-submit:false}") boolean checkCreditOnSubmit,
            @Value("${jde.cart.block-on-credit-exceeded:false}") boolean blockOnCreditExceeded,
            @Value("${jde.sales-order.document-type-code:SO}") String salesOrderDocumentTypeCode,
            @Value("${jde.sales-order.company:0001}") String salesOrderCompany,
            @Value("${jde.sales-order.document-company:00001}") String salesOrderDocumentCompany,
            @Value("${jde.sales-order.line-type-code:S}") String salesOrderLineTypeCode,
            @Value("${jde.sales-order.action-type:A}") String salesOrderActionType,
            @Value("${jde.sales-order.processing-version:ZJDE0001}") String salesOrderProcessingVersion,
            @Value("${jde.sales-order.default-requested-date-lead-days:30}") int requestedDateLeadDays) {
        this.repository = repository;
        this.soClient = soClient;
        this.ownerResolver = ownerResolver;
        this.defaultUnitOfMeasure = defaultUnitOfMeasure;
        this.defaultProcessingVersion = defaultProcessingVersion;
        this.checkAvailabilityOnValidate = checkAvailabilityOnValidate;
        this.checkAvailabilityOnAdd = checkAvailabilityOnAdd;
        this.checkCreditOnSubmit = checkCreditOnSubmit;
        this.blockOnCreditExceeded = blockOnCreditExceeded;
        this.salesOrderDocumentTypeCode = salesOrderDocumentTypeCode;
        this.salesOrderCompany = salesOrderCompany;
        this.salesOrderDocumentCompany = salesOrderDocumentCompany;
        this.salesOrderLineTypeCode = salesOrderLineTypeCode;
        this.salesOrderActionType = salesOrderActionType;
        this.salesOrderProcessingVersion = salesOrderProcessingVersion;
        this.requestedDateLeadDays = requestedDateLeadDays;
    }

    // =====================================================================
    // jde_create_current_sales_cart
    // =====================================================================

    public SalesCart createCart(Integer entityId, String businessUnit, String currencyCode) {
        CartOwner owner = ownerResolver.resolveCurrent();

        Optional<SalesCart> existing = repository.findBySessionId(owner.sessionId());
        if (existing.isPresent() && isBlockingExistingCart(existing.get())) {
            throw new CartOperationException(CartErrorCodes.CART_ALREADY_EXISTS,
                    "A shopping cart is already active for this session (status " + existing.get().status()
                            + "). Use jde_get_current_sales_cart to see it, or jde_clear_current_sales_cart "
                            + "to start over.");
        }

        CustomerDetail detail = soClient.getCustomerDetail(entityId);
        String resolvedCurrency = (currencyCode != null && !currencyCode.isBlank())
                ? currencyCode : detail.currencyCode();

        SalesCart cart = SalesCart.newCart(owner.sessionId(), owner.ownerId(), entityId, detail.name(),
                businessUnit, resolvedCurrency);
        return repository.save(cart);
    }

    private static boolean isBlockingExistingCart(SalesCart cart) {
        return cart.status() != CartStatus.ORDER_CREATED
                && cart.status() != CartStatus.CANCELLED
                && cart.status() != CartStatus.EXPIRED;
    }

    // =====================================================================
    // jde_get_current_sales_cart
    // =====================================================================

    public SalesCart getCart() {
        CartOwner owner = ownerResolver.resolveCurrent();
        SalesCart cart = repository.findBySessionId(owner.sessionId())
                .orElseThrow(() -> new CartOperationException(CartErrorCodes.CART_NOT_FOUND,
                        "No active shopping cart found for this session. Call jde_create_current_sales_cart "
                                + "to start one."));
        checkOwnership(cart, owner);
        return cart;
    }

    // =====================================================================
    // jde_add_item_to_current_sales_cart
    // =====================================================================

    public SalesCart addLine(Integer entityId, Integer itemId, String itemCatalog, String description,
                              Double quantity, String unitOfMeasure, String businessUnit, String currencyCode,
                              String returnAvailability) {
        CartOwner owner = ownerResolver.resolveCurrent();
        Optional<SalesCart> existing = repository.findBySessionId(owner.sessionId());

        SalesCart baseCart;
        if (existing.isEmpty()) {
            if (entityId == null) {
                throw new CartOperationException(CartErrorCodes.CART_NOT_FOUND,
                        "No active shopping cart for this session. Call jde_create_current_sales_cart first, "
                                + "or provide entityId here to start one automatically.");
            }
            baseCart = createCart(entityId, businessUnit, currencyCode);
        } else {
            baseCart = existing.get();
            checkOwnership(baseCart, owner);
            checkEditable(baseCart);
            if (entityId != null && !entityId.equals(baseCart.customerId())) {
                throw new CartOperationException(CartErrorCodes.CUSTOMER_MISMATCH,
                        "This cart already belongs to customer " + baseCart.customerId() + ". A cart can only "
                                + "hold lines for a single customer. Clear the cart first if you need to switch "
                                + "customers.");
            }
        }

        String effectiveBusinessUnit = (businessUnit != null && !businessUnit.isBlank())
                ? businessUnit : baseCart.businessUnit();
        String effectiveCurrency = (currencyCode != null && !currencyCode.isBlank())
                ? currencyCode : baseCart.currencyCode();
        String uom = (unitOfMeasure != null && !unitOfMeasure.isBlank()) ? unitOfMeasure : defaultUnitOfMeasure;

        if (effectiveBusinessUnit == null || effectiveBusinessUnit.isBlank()) {
            throw new CartOperationException(CartErrorCodes.CART_NOT_EDITABLE,
                    "No business unit / warehouse is known for this cart. Provide businessUnit.");
        }
        if (effectiveCurrency == null || effectiveCurrency.isBlank()) {
            throw new CartOperationException(CartErrorCodes.CURRENCY_NOT_RESOLVED,
                    "The transaction currency could not be resolved for this customer. Provide currencyCode.");
        }

        SalesCartLine newLine = resolveLine(itemId, itemCatalog, description, quantity, uom,
                effectiveBusinessUnit, baseCart.customerId(), effectiveCurrency, returnAvailability);

        return repository.update(owner.sessionId(), current -> {
            if (current == null) {
                throw new CartOperationException(CartErrorCodes.CART_NOT_FOUND,
                        "The cart was removed before this line could be added. Start a new one with "
                                + "jde_create_current_sales_cart.");
            }
            return current.withLineAdded(newLine).withVersionIncrementedAndTouched();
        });
    }

    private SalesCartLine resolveLine(Integer itemId, String itemCatalog, String description,
                                       Double quantity, String unitOfMeasure, String businessUnit,
                                       Integer entityId, String currencyCode, String returnAvailability) {
        boolean withAvailability = checkAvailabilityOnAdd || "Y".equalsIgnoreCase(returnAvailability);
        PriceQuote quote = withAvailability
                ? soClient.getItemPriceAndAvailability(itemId, itemCatalog, businessUnit, entityId, currencyCode,
                        quantity, unitOfMeasure, defaultProcessingVersion)
                : soClient.getCustomerItemPrice(itemId, itemCatalog, businessUnit, entityId, currencyCode,
                        quantity, unitOfMeasure, defaultProcessingVersion);

        BigDecimal availableQuantity = quote.availability().isEmpty()
                ? BigDecimal.ZERO : quote.availability().get(0).quantityAvailable();

        return new SalesCartLine(UUID.randomUUID().toString(), itemId, itemCatalog,
                description != null ? description : "", BigDecimal.valueOf(quantity), unitOfMeasure, businessUnit,
                quote.unitPrice(), quote.extendedPrice(), currencyCode, availableQuantity, Instant.now());
    }

    // =====================================================================
    // jde_update_current_sales_cart_item
    // =====================================================================

    public SalesCart updateLine(String lineId, Double quantity, String unitOfMeasure) {
        CartOwner owner = ownerResolver.resolveCurrent();
        SalesCart baseCart = repository.findBySessionId(owner.sessionId())
                .orElseThrow(() -> new CartOperationException(CartErrorCodes.CART_NOT_FOUND,
                        "No active shopping cart found for this session."));
        checkOwnership(baseCart, owner);
        checkEditable(baseCart);

        SalesCartLine existingLine = baseCart.findLine(lineId);
        if (existingLine == null) {
            throw new CartOperationException(CartErrorCodes.CART_LINE_NOT_FOUND,
                    "No line with id '" + lineId + "' was found in the current cart.");
        }

        BigDecimal newQuantity = quantity != null ? BigDecimal.valueOf(quantity) : existingLine.quantity();
        String newUnitOfMeasure = (unitOfMeasure != null && !unitOfMeasure.isBlank())
                ? unitOfMeasure : existingLine.unitOfMeasure();

        PriceQuote quote = checkAvailabilityOnAdd
                ? soClient.getItemPriceAndAvailability(existingLine.itemId(), existingLine.itemCatalog(),
                        existingLine.businessUnit(), baseCart.customerId(), existingLine.currencyCode(),
                        newQuantity, newUnitOfMeasure, defaultProcessingVersion)
                : soClient.getCustomerItemPrice(existingLine.itemId(), existingLine.itemCatalog(),
                        existingLine.businessUnit(), baseCart.customerId(), existingLine.currencyCode(),
                        newQuantity, newUnitOfMeasure, defaultProcessingVersion);

        BigDecimal availableQuantity = quote.availability().isEmpty()
                ? BigDecimal.ZERO : quote.availability().get(0).quantityAvailable();
        Instant now = Instant.now();

        return repository.update(owner.sessionId(), current -> {
            if (current == null) {
                throw new CartOperationException(CartErrorCodes.CART_NOT_FOUND, "The cart no longer exists.");
            }
            return current.withLineUpdated(lineId, line -> line
                            .withQuantity(newQuantity, newUnitOfMeasure)
                            .withRecalculatedPrice(quote.unitPrice(), quote.extendedPrice(), availableQuantity, now))
                    .withVersionIncrementedAndTouched();
        });
    }

    // =====================================================================
    // jde_remove_current_sales_cart_item
    // =====================================================================

    public SalesCart removeLine(String lineId) {
        CartOwner owner = ownerResolver.resolveCurrent();
        SalesCart baseCart = repository.findBySessionId(owner.sessionId())
                .orElseThrow(() -> new CartOperationException(CartErrorCodes.CART_NOT_FOUND,
                        "No active shopping cart found for this session."));
        checkOwnership(baseCart, owner);
        checkEditable(baseCart);

        if (baseCart.findLine(lineId) == null) {
            throw new CartOperationException(CartErrorCodes.CART_LINE_NOT_FOUND,
                    "No line with id '" + lineId + "' was found in the current cart.");
        }

        return repository.update(owner.sessionId(), current -> {
            if (current == null) {
                throw new CartOperationException(CartErrorCodes.CART_NOT_FOUND, "The cart no longer exists.");
            }
            return current.withLineRemoved(lineId).withVersionIncrementedAndTouched();
        });
    }

    // =====================================================================
    // jde_clear_current_sales_cart
    // =====================================================================

    /** @return el carrito tal como quedó (preservado si ORDER_CREATED) o null si se eliminó. */
    public SalesCart clearCart() {
        CartOwner owner = ownerResolver.resolveCurrent();
        SalesCart cart = repository.findBySessionId(owner.sessionId())
                .orElseThrow(() -> new CartOperationException(CartErrorCodes.CART_NOT_FOUND,
                        "No active shopping cart found for this session -- nothing to clear."));
        checkOwnership(cart, owner);

        if (cart.status() == CartStatus.ORDER_CREATED) {
            return cart;
        }
        repository.deleteBySessionId(owner.sessionId());
        return null;
    }

    // =====================================================================
    // jde_validate_current_sales_cart
    // =====================================================================

    /**
     * Revalida cada línea contra JDE (precio siempre; disponibilidad si
     * jde.cart.check-availability-on-validate=true) y detecta cambios desde
     * la última consulta. Si hay cambios, el carrito vuelve/permanece OPEN y
     * requiresReconfirmation=true; si no, pasa a READY_FOR_CONFIRMATION. No
     * incrementa version -- validate resincroniza contra JDE, no representa
     * nueva intención del usuario (eso es lo que expectedCartVersion en
     * jde_submit_current_sales_cart protege).
     */
    public CartValidationOutcome validateCart() {
        CartOwner owner = ownerResolver.resolveCurrent();
        SalesCart baseCart = repository.findBySessionId(owner.sessionId())
                .orElseThrow(() -> new CartOperationException(CartErrorCodes.CART_NOT_FOUND,
                        "No active shopping cart found for this session."));
        checkOwnership(baseCart, owner);
        checkEditable(baseCart);

        if (baseCart.lines().isEmpty()) {
            throw new CartOperationException(CartErrorCodes.CART_EMPTY,
                    "The cart has no lines to validate. Add at least one item first.");
        }

        List<CartChange> changes = new ArrayList<>();
        List<SalesCartLine> recalculatedLines = new ArrayList<>(baseCart.lines().size());
        Instant now = Instant.now();

        for (SalesCartLine line : baseCart.lines()) {
            PriceQuote quote = checkAvailabilityOnValidate
                    ? soClient.getItemPriceAndAvailability(line.itemId(), line.itemCatalog(), line.businessUnit(),
                            baseCart.customerId(), line.currencyCode(), line.quantity(), line.unitOfMeasure(),
                            defaultProcessingVersion)
                    : soClient.getCustomerItemPrice(line.itemId(), line.itemCatalog(), line.businessUnit(),
                            baseCart.customerId(), line.currencyCode(), line.quantity(), line.unitOfMeasure(),
                            defaultProcessingVersion);

            BigDecimal newAvailableQuantity = quote.availability().isEmpty()
                    ? line.availableQuantity() : quote.availability().get(0).quantityAvailable();

            if (line.unitPrice() == null || quote.unitPrice().compareTo(line.unitPrice()) != 0) {
                changes.add(new CartChange(line.lineId(), "unitPrice",
                        String.valueOf(line.unitPrice()), String.valueOf(quote.unitPrice())));
            }
            if (checkAvailabilityOnValidate && newAvailableQuantity.compareTo(line.quantity()) < 0) {
                changes.add(new CartChange(line.lineId(), "availableQuantity",
                        String.valueOf(line.availableQuantity()), String.valueOf(newAvailableQuantity)));
            }

            recalculatedLines.add(line.withRecalculatedPrice(quote.unitPrice(), quote.extendedPrice(),
                    newAvailableQuantity, now));
        }

        boolean requiresReconfirmation = !changes.isEmpty();
        CartStatus newStatus = requiresReconfirmation ? CartStatus.OPEN : CartStatus.READY_FOR_CONFIRMATION;

        SalesCart updated = repository.update(owner.sessionId(), current -> {
            if (current == null) {
                throw new CartOperationException(CartErrorCodes.CART_NOT_FOUND, "The cart no longer exists.");
            }
            return current.withLinesReplaced(recalculatedLines).withStatus(newStatus);
        });

        return new CartValidationOutcome(updated, requiresReconfirmation, changes);
    }

    // =====================================================================
    // jde_submit_current_sales_cart
    // =====================================================================

    /**
     * Valida todas las precondiciones de negocio, marca el carrito SUBMITTING
     * atómicamente y arma el payload de creación de pedido -- pero NO llama
     * al Gateway (eso es responsabilidad de JdeSalesCartTools, para poder
     * enrutarlo a través de LongRunningTaskRegistry cuando
     * jde.sales-order.submit.async.enabled=true, algo que esta clase no
     * conoce ni necesita conocer).
     *
     * Determinístico: si el carrito ya está SUBMITTING con la misma version
     * (una segunda llamada -- p.ej. un poll de una tarea async en curso, o un
     * reintento del cliente MCP), reconstruye el mismo request/externalReference
     * sin volver a mutar el estado, en vez de fallar con "ya hay un envío en
     * curso". Eso es lo que permite a la tool reconectar con la misma tarea
     * del registry por key sin depender de un estado intermedio guardado acá.
     */
    public SubmissionPreparation prepareSubmission(long expectedCartVersion) {
        CartOwner owner = ownerResolver.resolveCurrent();
        SalesCart cart = repository.findBySessionId(owner.sessionId())
                .orElseThrow(() -> new CartOperationException(CartErrorCodes.CART_NOT_FOUND,
                        "No active shopping cart found for this session."));
        checkOwnership(cart, owner);

        if (cart.status() == CartStatus.ORDER_CREATED) {
            return SubmissionPreparation.recovered(cart, recoveredRef(cart.createdOrder()));
        }

        if (cart.status() == CartStatus.SUBMITTING) {
            if (cart.version() != expectedCartVersion) {
                throw new CartVersionConflictException(cart.version());
            }
            String externalReference = buildExternalReference(cart);
            return SubmissionPreparation.ready(cart, buildRequest(cart, externalReference), externalReference, "");
        }

        if (!cart.isEditable()) {
            throw new CartOperationException(CartErrorCodes.ORDER_SUBMISSION_FAILED,
                    "The cart cannot be submitted in its current status (" + cart.status() + ").");
        }
        if (cart.lines().isEmpty()) {
            throw new CartOperationException(CartErrorCodes.CART_EMPTY,
                    "The cart has no lines -- add at least one item before submitting.");
        }
        if (cart.version() != expectedCartVersion) {
            throw new CartVersionConflictException(cart.version());
        }
        if (cart.currencyCode() == null || cart.currencyCode().isBlank()) {
            throw new CartOperationException(CartErrorCodes.CURRENCY_NOT_RESOLVED,
                    "The transaction currency could not be resolved for this cart.");
        }

        String warning = checkCredit(cart);

        SalesCart submitting = repository.update(owner.sessionId(), current -> {
            if (current == null) {
                throw new CartOperationException(CartErrorCodes.CART_NOT_FOUND, "The cart no longer exists.");
            }
            if (current.status() == CartStatus.ORDER_CREATED || current.status() == CartStatus.SUBMITTING) {
                return current;
            }
            if (!current.isEditable()) {
                throw new CartOperationException(CartErrorCodes.ORDER_SUBMISSION_FAILED,
                        "The cart cannot be submitted in its current status (" + current.status() + ").");
            }
            if (current.version() != expectedCartVersion) {
                throw new CartVersionConflictException(current.version());
            }
            return current.withStatus(CartStatus.SUBMITTING);
        });

        if (submitting.status() == CartStatus.ORDER_CREATED) {
            return SubmissionPreparation.recovered(submitting, recoveredRef(submitting.createdOrder()));
        }

        String externalReference = buildExternalReference(submitting);
        return SubmissionPreparation.ready(submitting, buildRequest(submitting, externalReference),
                externalReference, warning);
    }

    /** Llamado por la tool tras una creación de pedido exitosa (síncrona o vía LongRunningTaskRegistry). */
    public SalesCart finalizeSubmission(String sessionId, CreateSalesOrderResponse response, String externalReference) {
        CreatedOrderRef ref = new CreatedOrderRef(response.documentCompany(), response.orderNumber(),
                response.orderType(), externalReference, false);
        return repository.update(sessionId, current -> current == null
                ? null : current.withCreatedOrder(ref).withStatus(CartStatus.ORDER_CREATED));
    }

    /**
     * Llamado por la tool si la creación de pedido falla -- no hay ninguna
     * transacción JDE que revertir (el spec original lo aclara), solo se deja
     * el carrito consistente (OPEN) para que el usuario pueda reintentar.
     */
    public void failSubmission(String sessionId) {
        repository.update(sessionId, current -> current == null
                ? null : (current.status() == CartStatus.SUBMITTING ? current.withStatus(CartStatus.OPEN) : current));
    }

    private static CreatedOrderRef recoveredRef(CreatedOrderRef ref) {
        CreatedOrderRef safe = ref != null ? ref : CreatedOrderRef.empty();
        return new CreatedOrderRef(safe.company(), safe.orderNumber(), safe.orderType(), safe.externalReference(), true);
    }

    private static String buildExternalReference(SalesCart cart) {
        return "MCP-" + cart.cartId() + "-" + cart.version();
    }

    private String checkCredit(SalesCart cart) {
        if (!checkCreditOnSubmit) {
            return "";
        }
        CustomerCreditSummary credit = soClient.getCustomerCreditInfo(cart.customerId());
        BigDecimal projectedExposure = credit.totalExposure().add(cartTotal(cart));
        boolean exceeded = credit.creditLimit().signum() > 0 && projectedExposure.compareTo(credit.creditLimit()) > 0;
        if (!exceeded) {
            return "";
        }
        if (blockOnCreditExceeded) {
            throw new CartOperationException(CartErrorCodes.CREDIT_LIMIT_EXCEEDED,
                    "This order would exceed the customer's credit limit (" + credit.creditLimit() + " "
                            + cart.currencyCode() + "). Ask a supervisor to approve it, or reduce the order.");
        }
        return "Warning: this order exceeds the customer's credit limit.";
    }

    private static BigDecimal cartTotal(SalesCart cart) {
        BigDecimal total = BigDecimal.ZERO;
        for (SalesCartLine line : cart.lines()) {
            if (line.extendedPrice() != null) {
                total = total.add(line.extendedPrice());
            }
        }
        return total;
    }

    /**
     * dateOrdered es la fecha comercial del pedido (hoy). dateRequested es la
     * fecha en la que el cliente solicita recibir/disponer de los productos
     * -- NO es "hoy": si coincide con dateOrdered, JDE no tiene margen para
     * calcular la fecha de pick/promised-ship contra el lead time del
     * business unit/artículo y devuelve un warning (confirmado contra un
     * envío real -- ver .claude/generaciondepedido.md). Por eso se calcula
     * como hoy + jde.sales-order.default-requested-date-lead-days, nunca
     * igual a dateOrdered.
     */
    private CreateSalesOrderRequest buildRequest(SalesCart cart, String externalReference) {
        String headerBusinessUnit = (cart.businessUnit() != null && !cart.businessUnit().isBlank())
                ? cart.businessUnit() : cart.lines().get(0).businessUnit();
        LocalDate dateOrdered = LocalDate.now();
        LocalDate dateRequested = dateOrdered.plusDays(requestedDateLeadDays);

        List<SalesOrderLineRequest> lines = new ArrayList<>(cart.lines().size());
        for (SalesCartLine line : cart.lines()) {
            lines.add(new SalesOrderLineRequest(line.businessUnit(), line.quantity(), salesOrderLineTypeCode,
                    externalReference, resolveItemProduct(line)));
        }

        return new CreateSalesOrderRequest(headerBusinessUnit, cart.customerId(), cart.customerId(), cart.customerId(),
                externalReference, dateRequested, dateOrdered, salesOrderCompany, salesOrderDocumentCompany,
                cart.currencyCode(), salesOrderDocumentTypeCode, salesOrderProcessingVersion, salesOrderActionType,
                lines);
    }

    /**
     * itemProduct es el identificador que processSalesOrderV5 espera para el
     * producto -- confirmado empíricamente (ver .claude/generaciondepedido.md)
     * que coincide con itemCatalog (sin padding) cuando está disponible.
     * Fallback a itemId cuando itemCatalog no vino informado en la línea.
     */
    private static String resolveItemProduct(SalesCartLine line) {
        if (line.itemCatalog() != null && !line.itemCatalog().isBlank()) {
            return line.itemCatalog().trim();
        }
        return String.valueOf(line.itemId());
    }

    // =====================================================================
    // Helpers compartidos
    // =====================================================================

    void checkOwnership(SalesCart cart, CartOwner owner) {
        if (!cart.ownerId().equals(owner.ownerId())) {
            throw new CartOperationException(CartErrorCodes.CART_ACCESS_DENIED,
                    "This cart does not belong to the current authenticated user.");
        }
        if (cart.isExpired()) {
            throw new CartOperationException(CartErrorCodes.CART_EXPIRED,
                    "The shopping cart expired due to inactivity. Start a new one with "
                            + "jde_create_current_sales_cart.");
        }
    }

    void checkEditable(SalesCart cart) {
        if (!cart.isEditable()) {
            throw new CartOperationException(CartErrorCodes.CART_NOT_EDITABLE,
                    "The cart cannot be modified in its current status (" + cart.status() + ").");
        }
    }
}
