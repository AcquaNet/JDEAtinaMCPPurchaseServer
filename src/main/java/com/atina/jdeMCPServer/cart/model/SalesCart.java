package com.atina.jdeMCPServer.cart.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Carrito de compras en memoria, un único carrito activo por sesión MCP
 * (sessionId es la clave del repositorio, ver SalesCartRepository). Record
 * inmutable como el resto del proyecto (sin Lombok, sin setters) -- cada
 * mutación de negocio es un método with* que devuelve una instancia nueva,
 * mismo patrón que LongRunningTask.withCompleted/withFailed
 * (com.atina.jdeMCPServer.mcp.tasks). El repositorio aplica estas mutaciones
 * de forma atómica vía ConcurrentHashMap.compute().
 *
 * tenantId: no existe hoy ninguna fuente real de multi-tenant en el proyecto
 * -- el campo se incluye por fidelidad al modelo pedido, pero se resuelve a
 * "" hasta que exista una fuente real (ver CartOwnerResolver).
 * shipToId: se resuelve igual a customerId en esta primera versión --
 * invoicedTo/deliverTo/shipTo pueden ser entidades distintas en JDE, pero el
 * carrito no tiene hoy una forma de que el usuario informe una dirección de
 * envío separada.
 */
public record SalesCart(
        String cartId,
        String sessionId,
        String ownerId,
        String tenantId,
        Integer customerId,
        String customerName,
        Integer shipToId,
        String businessUnit,
        String company,
        String orderType,
        String currencyCode,
        List<SalesCartLine> lines,
        CartStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        CreatedOrderRef createdOrder
) {

    /**
     * expiresAt se fija provisoriamente a "now" -- SalesCartRepository.save()
     * lo reemplaza por now+ttl antes de persistir (mismo criterio que
     * PendingPurchaseOrderStore: el repositorio, no el caller, es dueño de la
     * lógica de expiración).
     */
    public static SalesCart newCart(String sessionId, String ownerId, Integer customerId, String customerName,
                                     String businessUnit, String currencyCode) {
        Instant now = Instant.now();
        return new SalesCart(
                UUID.randomUUID().toString(),
                sessionId,
                ownerId,
                "",
                customerId,
                customerName,
                customerId,
                businessUnit != null ? businessUnit : "",
                "",
                "",
                currencyCode != null ? currencyCode : "",
                List.of(),
                CartStatus.OPEN,
                0L,
                now,
                now,
                now,
                null
        );
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isEditable() {
        return status == CartStatus.OPEN || status == CartStatus.READY_FOR_CONFIRMATION;
    }

    public SalesCartLine findLine(String lineId) {
        for (SalesCartLine line : lines) {
            if (line.lineId().equals(lineId)) {
                return line;
            }
        }
        return null;
    }

    public SalesCart withLineAdded(SalesCartLine line) {
        List<SalesCartLine> newLines = new ArrayList<>(lines);
        newLines.add(line);
        return withLines(newLines);
    }

    public SalesCart withLineUpdated(String lineId, UnaryOperator<SalesCartLine> updater) {
        List<SalesCartLine> newLines = new ArrayList<>(lines.size());
        for (SalesCartLine line : lines) {
            newLines.add(line.lineId().equals(lineId) ? updater.apply(line) : line);
        }
        return withLines(newLines);
    }

    public SalesCart withLineRemoved(String lineId) {
        List<SalesCartLine> newLines = new ArrayList<>(lines.size());
        for (SalesCartLine line : lines) {
            if (!line.lineId().equals(lineId)) {
                newLines.add(line);
            }
        }
        return withLines(newLines);
    }

    /** Usado por SalesCartService.validateCart tras recalcular precio/disponibilidad de cada línea. */
    public SalesCart withLinesReplaced(List<SalesCartLine> newLines) {
        return withLines(newLines);
    }

    private SalesCart withLines(List<SalesCartLine> newLines) {
        return new SalesCart(cartId, sessionId, ownerId, tenantId, customerId, customerName, shipToId,
                businessUnit, company, orderType, currencyCode, List.copyOf(newLines), status, version,
                createdAt, updatedAt, expiresAt, createdOrder);
    }

    public SalesCart withStatus(CartStatus newStatus) {
        return new SalesCart(cartId, sessionId, ownerId, tenantId, customerId, customerName, shipToId,
                businessUnit, company, orderType, currencyCode, lines, newStatus, version,
                createdAt, Instant.now(), expiresAt, createdOrder);
    }

    public SalesCart withCurrencyCode(String newCurrencyCode) {
        return new SalesCart(cartId, sessionId, ownerId, tenantId, customerId, customerName, shipToId,
                businessUnit, company, orderType, newCurrencyCode, lines, status, version,
                createdAt, Instant.now(), expiresAt, createdOrder);
    }

    /**
     * Incrementa la versión y actualiza updatedAt tras cualquier mutación de
     * líneas. Si el carrito estaba READY_FOR_CONFIRMATION (ya validado), la
     * mutación lo vuelve a OPEN -- fuerza a revalidar antes de poder
     * confirmar de nuevo, tal como pide el spec original.
     */
    public SalesCart withVersionIncrementedAndTouched() {
        CartStatus newStatus = status == CartStatus.READY_FOR_CONFIRMATION ? CartStatus.OPEN : status;
        return new SalesCart(cartId, sessionId, ownerId, tenantId, customerId, customerName, shipToId,
                businessUnit, company, orderType, currencyCode, lines, newStatus, version + 1,
                createdAt, Instant.now(), expiresAt, createdOrder);
    }

    public SalesCart withCreatedOrder(CreatedOrderRef ref) {
        return new SalesCart(cartId, sessionId, ownerId, tenantId, customerId, customerName, shipToId,
                businessUnit, company, orderType, currencyCode, lines, status, version,
                createdAt, Instant.now(), expiresAt, ref);
    }

    /** Usado por SalesCartRepository para implementar el TTL deslizante (se renueva en cada save/update). */
    public SalesCart withExpiresAt(Instant newExpiresAt) {
        return new SalesCart(cartId, sessionId, ownerId, tenantId, customerId, customerName, shipToId,
                businessUnit, company, orderType, currencyCode, lines, status, version,
                createdAt, updatedAt, newExpiresAt, createdOrder);
    }
}
