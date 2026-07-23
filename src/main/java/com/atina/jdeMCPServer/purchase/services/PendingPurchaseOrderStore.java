package com.atina.jdeMCPServer.purchase.services;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Cache en memoria de las purchase orders devueltas por
 * jde_list_pending_purchase_orders, indexada por su clave compuesta JDE
 * (documentCompanyKeyOrderNo + documentOrderTypeCode + documentOrderInvoiceNumber
 * + documentSuffix). Equivalente al Object Store que usaba Mulesoft: guarda el
 * registro crudo de getPurchaseOrdersForApprover para que jde_approve_purchase_order
 * / jde_reject_purchase_order puedan recuperar businessUnitCode, statusApproval,
 * amountGross y approvalRoutingCodePurchaseOrder sin pedírselos al caller.
 *
 * Cada entrada tiene un TTL (jde.purchase.pending-order-ttl-minutes): vence tanto
 * si se la vuelve a pedir después de expirada (evicción perezosa en get()) como si
 * nadie la vuelve a pedir nunca -- una orden listada y nunca aprobada/rechazada se
 * purga igual via el barrido periódico {@link #purgeExpired()}.
 */
@Component
public class PendingPurchaseOrderStore {

    private static final Logger log = LoggerFactory.getLogger(PendingPurchaseOrderStore.class);

    private record CacheEntry(JsonNode order, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final ConcurrentHashMap<String, CacheEntry> pendingOrders = new ConcurrentHashMap<>();
    private final Duration ttl;

    public PendingPurchaseOrderStore(
            @Value("${jde.purchase.pending-order-ttl-minutes:45}") long ttlMinutes) {
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public static String buildKey(JsonNode order) {
        return buildKey(
                order.path("documentCompanyKeyOrderNo").asText(""),
                order.path("documentOrderTypeCode").asText(""),
                order.path("documentOrderInvoiceNumber").asText(""),
                order.path("documentSuffix").asText("")
        );
    }

    public static String buildKey(String documentCompanyKeyOrderNo,
                                   String documentOrderTypeCode,
                                   String documentOrderInvoiceNumber,
                                   String documentSuffix) {
        return (documentCompanyKeyOrderNo != null ? documentCompanyKeyOrderNo : "")
                + (documentOrderTypeCode != null ? documentOrderTypeCode : "")
                + (documentOrderInvoiceNumber != null ? documentOrderInvoiceNumber : "")
                + (documentSuffix != null ? documentSuffix : "");
    }

    public void put(JsonNode order) {
        pendingOrders.put(buildKey(order), new CacheEntry(order, Instant.now().plus(ttl)));
    }

    public Optional<JsonNode> get(String key) {
        CacheEntry entry = pendingOrders.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired()) {
            pendingOrders.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.order());
    }

    public void remove(String key) {
        pendingOrders.remove(key);
    }

    /**
     * Barrido periódico: purga entradas vencidas aunque nadie las vuelva a pedir
     * (orden listada pero nunca aprobada/rechazada). El intervalo es independiente
     * del TTL, solo debe ser menor para que la purga sea razonablemente oportuna.
     */
    @Scheduled(
            fixedRateString = "${jde.purchase.pending-order-cleanup-interval-minutes:10}",
            timeUnit = TimeUnit.MINUTES
    )
    public void purgeExpired() {
        int before = pendingOrders.size();
        pendingOrders.values().removeIf(CacheEntry::isExpired);
        int removed = before - pendingOrders.size();
        if (removed > 0) {
            log.debug("Purgadas {} purchase orders vencidas de la caché (TTL {} min)", removed, ttl.toMinutes());
        }
    }
}
