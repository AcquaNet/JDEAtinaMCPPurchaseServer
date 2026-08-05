package com.atina.jdeMCPServer.cart.services;

import com.atina.jdeMCPServer.cart.model.CartErrorCodes;
import com.atina.jdeMCPServer.cart.model.SalesCart;
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
import java.util.function.UnaryOperator;

/**
 * Almacenamiento en memoria del carrito activo por sesión MCP, calcado de
 * com.atina.jdeMCPServer.purchase.services.PendingPurchaseOrderStore (mismo
 * criterio: ConcurrentHashMap keyed por sessionId, TTL configurable
 * convertido a Duration en el constructor, evicción perezosa en
 * findBySessionId + barrido periódico @Scheduled). Se pierde íntegro si el
 * proceso se reinicia -- a propósito, ver limitaciones de idempotencia
 * documentadas en SalesCartService.submitCart.
 *
 * update(sessionId, mutator) usa ConcurrentHashMap.compute(), que corre el
 * mutator de forma atómica respecto de otras operaciones sobre la misma key
 * (bloqueo interno por bucket) -- evita perder un incremento de versión entre
 * dos llamadas casi simultáneas del mismo sessionId, sin necesitar
 * synchronized sobre el propio SalesCart (que no serviría de nada: al ser un
 * record inmutable, cada mutación reemplaza la instancia en el mapa).
 */
@Component
public class InMemorySalesCartRepository implements SalesCartRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemorySalesCartRepository.class);

    private final ConcurrentHashMap<String, SalesCart> carts = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxActiveCarts;

    public InMemorySalesCartRepository(
            @Value("${jde.cart.ttl-minutes:120}") long ttlMinutes,
            @Value("${jde.cart.max-active-carts:500}") int maxActiveCarts) {
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.maxActiveCarts = maxActiveCarts;
    }

    @Override
    public Optional<SalesCart> findBySessionId(String sessionId) {
        SalesCart cart = carts.get(sessionId);
        if (cart == null) {
            return Optional.empty();
        }
        if (cart.isExpired()) {
            carts.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(cart);
    }

    @Override
    public SalesCart save(SalesCart cart) {
        if (!carts.containsKey(cart.sessionId()) && carts.size() >= maxActiveCarts) {
            throw new CartOperationException(CartErrorCodes.CART_LIMIT_EXCEEDED,
                    "Maximum number of active shopping carts (" + maxActiveCarts + ") has been reached. " +
                            "Try again later.");
        }
        SalesCart withExpiry = cart.withExpiresAt(Instant.now().plus(ttl));
        carts.put(withExpiry.sessionId(), withExpiry);
        return withExpiry;
    }

    @Override
    public SalesCart update(String sessionId, UnaryOperator<SalesCart> mutator) {
        return carts.compute(sessionId, (key, current) -> {
            SalesCart next = mutator.apply(current);
            return next == null ? null : next.withExpiresAt(Instant.now().plus(ttl));
        });
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        carts.remove(sessionId);
    }

    @Override
    public int activeCount() {
        return carts.size();
    }

    /**
     * Barrido periódico: purga carritos vencidos aunque nadie los vuelva a
     * pedir -- mismo criterio que PendingPurchaseOrderStore.purgeExpired().
     */
    @Override
    @Scheduled(
            fixedRateString = "${jde.cart.cleanup-interval-minutes:15}",
            timeUnit = TimeUnit.MINUTES
    )
    public void removeExpired() {
        int before = carts.size();
        carts.values().removeIf(SalesCart::isExpired);
        int removed = before - carts.size();
        if (removed > 0) {
            log.debug("Purgados {} carritos vencidos de la caché (TTL {} min)", removed, ttl.toMinutes());
        }
    }
}
