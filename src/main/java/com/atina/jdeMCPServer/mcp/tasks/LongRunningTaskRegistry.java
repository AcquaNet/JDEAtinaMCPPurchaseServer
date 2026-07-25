package com.atina.jdeMCPServer.mcp.tasks;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Motor genérico en memoria (sin persistencia -- a propósito, ver
 * jde.mcp.tasks.* en application.properties) para operaciones largas
 * expuestas como MCP tools que no pueden bloquear el request original hasta
 * terminar. Modela los mismos campos/estados que el Task real de SEP-2663
 * ("Tasks Extension", https://modelcontextprotocol.io/seps/2663-tasks-extension)
 * para que migrar a esa extensión el día que el SDK Java de MCP y los
 * clientes (Slack, Claude Desktop, etc.) la soporten sea reemplazar el
 * adaptador de exposición, no rediseñar este motor.
 *
 * Bean único compartido entre tools (no genérico a nivel de clase -- mismo
 * criterio que {@link com.atina.jdeMCPServer.gateway.RequestCoalescer}, que
 * este motor extiende conceptualmente: Object como tipo de resultado en el
 * storage, con métodos genéricos que documentan el tipo esperado en el call
 * site vía el propio Supplier<T>). El pool de ejecución está dimensionado
 * hoy para un solo consumidor real (jde_list_pending_purchase_orders) --
 * revisar el tamaño si se conecta un segundo tool lento.
 *
 * IMPORTANTE para quien use este registry: el Supplier<T> que se le pasa a
 * getOrStart corre en un hilo de este pool propio, SIN el RequestContextHolder
 * del HttpServletRequest original (JdeAuthService depende de él). Cualquier
 * dato que dependa del request actual (token JDE, identidad del approver,
 * etc.) debe resolverse ANTES de llamar a getOrStart, en el hilo del request
 * original, y pasarse como dato plano capturado por el Supplier -- nunca
 * resolverlo dentro del propio Supplier.
 */
@Component
public class LongRunningTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(LongRunningTaskRegistry.class);

    private record Entry(CompletableFuture<Object> future, AtomicReference<LongRunningTask> snapshot) {
    }

    private final ConcurrentHashMap<String, Entry> byBusinessKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> byTaskId = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Duration completedGracePeriod;

    public LongRunningTaskRegistry(
            @Value("${jde.mcp.tasks.executor-pool-size:2}") int executorPoolSize,
            @Value("${jde.mcp.tasks.completed-grace-period-seconds:30}") long completedGracePeriodSeconds) {
        AtomicInteger threadCounter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(executorPoolSize, runnable -> {
            Thread thread = new Thread(runnable, "long-running-task-worker-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.completedGracePeriod = Duration.ofSeconds(completedGracePeriodSeconds);
    }

    /**
     * Si no hay una tarea en curso para {@code key}, arranca {@code work} en
     * background (sin bloquear) y espera acotado por {@code initialWait} --
     * si el trabajo termina dentro de ese lapso, devuelve el resultado final
     * directo (ahorra una segunda vuelta del caller cuando el trabajo es
     * rápido); si no, devuelve el snapshot actual (WORKING). Si ya hay una
     * tarea en curso o recién terminada (dentro del grace period) para esa
     * key, devuelve su snapshot sin arrancar nada nuevo (con el mismo
     * {@code initialWait} aplicado, por si justo está por terminar). Si la
     * tarea existente ya terminó y superó el grace period, se descarta y se
     * arranca una nueva (con un taskId nuevo -- nunca se reusa el de un
     * intento anterior).
     */
    public <T> LongRunningTask getOrStart(String key, Duration hardTimeout, Long pollIntervalMs,
                                           Duration initialWait, Supplier<T> work) {
        Entry entry = byBusinessKey.compute(key, (k, existing) -> {
            if (existing != null && existing.snapshot().get().isTerminal()
                    && isPastGrace(existing.snapshot().get())) {
                byTaskId.remove(existing.snapshot().get().taskId());
                existing = null;
            }
            if (existing != null) {
                return existing;
            }
            return startNew(hardTimeout, pollIntervalMs, work);
        });
        byTaskId.putIfAbsent(entry.snapshot().get().taskId(), entry);

        // No confiar en que whenComplete (que corre en el hilo que completa el
        // future, potencialmente distinto a este) ya haya actualizado el
        // snapshot para cuando este get() desbloquea -- son dos listeners
        // independientes sobre el mismo future, sin orden garantizado entre
        // ellos. Por eso acá se construye el snapshot terminal directo a
        // partir del resultado/excepción de ESTE get(), con updateAndGet
        // idempotente (isTerminal() ya en true = no-op) para no pisar lo que
        // whenComplete haya llegado a setear primero.
        try {
            Object result = entry.future().get(initialWait.toMillis(), TimeUnit.MILLISECONDS);
            return entry.snapshot().updateAndGet(prev -> prev.isTerminal() ? prev : prev.withCompleted(result));
        } catch (TimeoutException e) {
            return entry.snapshot().get(); // sigue en curso
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return entry.snapshot().get();
        } catch (java.util.concurrent.ExecutionException e) {
            String message = describeError(e.getCause() != null ? e.getCause() : e);
            return entry.snapshot().updateAndGet(prev -> prev.isTerminal() ? prev : prev.withFailed(message));
        }
    }

    private <T> Entry startNew(Duration hardTimeout, Long pollIntervalMs, Supplier<T> work) {
        String taskId = UUID.randomUUID().toString();
        AtomicReference<LongRunningTask> snapshot = new AtomicReference<>(
                LongRunningTask.working(taskId, hardTimeout.toMillis(), pollIntervalMs));

        @SuppressWarnings("unchecked")
        CompletableFuture<Object> future = (CompletableFuture<Object>) CompletableFuture
                .supplyAsync(work, executor)
                .orTimeout(hardTimeout.toMillis(), TimeUnit.MILLISECONDS);

        future.whenComplete((result, error) -> {
            if (error == null) {
                snapshot.set(snapshot.get().withCompleted(result));
            } else {
                String message = describeError(error);
                log.warn("Tarea {} terminó con error: {}", taskId, message);
                snapshot.set(snapshot.get().withFailed(message));
            }
        });

        return new Entry(future, snapshot);
    }

    private static String describeError(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return "La operación superó el tiempo máximo de espera.";
        }
        String message = cause.getMessage();
        return (message != null && !message.isBlank()) ? message : cause.getClass().getSimpleName();
    }

    private boolean isPastGrace(LongRunningTask task) {
        return Instant.now().isAfter(task.lastUpdatedAt().plus(completedGracePeriod));
    }

    /**
     * Barrido periódico: purga tareas terminales viejas de ambos índices aunque
     * nadie las vuelva a consultar -- mismo criterio que
     * {@link com.atina.jdeMCPServer.purchase.services.PendingPurchaseOrderStore#purgeExpired()}.
     */
    @Scheduled(
            fixedRateString = "${jde.mcp.tasks.cleanup-interval-minutes:5}",
            timeUnit = TimeUnit.MINUTES
    )
    public void purgeStaleTasks() {
        int before = byBusinessKey.size();
        byBusinessKey.entrySet().removeIf(e -> {
            LongRunningTask snapshot = e.getValue().snapshot().get();
            boolean stale = snapshot.isTerminal() && isPastGrace(snapshot);
            if (stale) {
                byTaskId.remove(snapshot.taskId());
            }
            return stale;
        });
        int removed = before - byBusinessKey.size();
        if (removed > 0) {
            log.debug("Purgadas {} tareas terminales vencidas del registry", removed);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
