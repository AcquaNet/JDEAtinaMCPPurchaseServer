package com.atina.jdeMCPServer.mcp.tasks;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningTaskRegistryTest {

    private LongRunningTaskRegistry newRegistry(long gracePeriodSeconds) {
        return new LongRunningTaskRegistry(2, gracePeriodSeconds);
    }

    @Test
    void supplierQueFallaRapido_devuelveFailedEnLaPrimeraLlamada() {
        // Reproduce una carrera real encontrada probando en vivo: si el trabajo
        // falla casi al instante, el get() acotado de getOrStart no debe
        // devolver un snapshot WORKING obsoleto por ganarle la carrera al
        // callback whenComplete -- debe reportar FAILED ya en esta misma llamada.
        LongRunningTaskRegistry registry = newRegistry(30);

        LongRunningTask task = registry.getOrStart("key-falla-rapido", Duration.ofMinutes(1), 5000L,
                Duration.ofSeconds(2), () -> {
                    throw new IllegalStateException("fallo inmediato simulado");
                });

        assertEquals(TaskStatus.FAILED, task.status());
        assertEquals("fallo inmediato simulado", task.error());
    }

    @Test
    void supplierRapido_devuelveCompletedEnLaPrimeraLlamada() {
        LongRunningTaskRegistry registry = newRegistry(30);

        LongRunningTask task = registry.getOrStart("key-rapido", Duration.ofMinutes(1), 5000L,
                Duration.ofSeconds(2), () -> "resultado-ok");

        assertEquals(TaskStatus.COMPLETED, task.status());
        assertEquals("resultado-ok", task.result());
        assertNotNull(task.taskId());
    }

    @Test
    void supplierLento_devuelveWorkingLuegoCompleted() throws InterruptedException {
        LongRunningTaskRegistry registry = newRegistry(30);
        CountDownLatch release = new CountDownLatch(1);

        LongRunningTask first = registry.getOrStart("key-lento", Duration.ofMinutes(1), 5000L,
                Duration.ofMillis(200), () -> {
                    await(release);
                    return "resultado-tardio";
                });
        assertEquals(TaskStatus.WORKING, first.status());
        String firstTaskId = first.taskId();

        release.countDown();
        Thread.sleep(300);

        LongRunningTask second = registry.getOrStart("key-lento", Duration.ofMinutes(1), 5000L,
                Duration.ofMillis(200), () -> {
                    throw new AssertionError("no debe arrancar un job nuevo mientras el anterior sigue vivo");
                });
        assertEquals(TaskStatus.COMPLETED, second.status());
        assertEquals("resultado-tardio", second.result());
        assertEquals(firstTaskId, second.taskId());
    }

    @Test
    void supplierQueExcedeTimeout_terminaEnFailed() throws InterruptedException {
        LongRunningTaskRegistry registry = newRegistry(30);
        CountDownLatch neverRelease = new CountDownLatch(1);

        LongRunningTask task = registry.getOrStart("key-timeout", Duration.ofMillis(200), 5000L,
                Duration.ofMillis(50), () -> {
                    await(neverRelease);
                    return "nunca-llega";
                });
        assertEquals(TaskStatus.WORKING, task.status());

        Thread.sleep(500);

        LongRunningTask afterTimeout = registry.getOrStart("key-timeout", Duration.ofMillis(200), 5000L,
                Duration.ofMillis(50), () -> {
                    throw new AssertionError("no deberia hacer falta arrancar otro mientras se lee el estado FAILED");
                });
        assertEquals(TaskStatus.FAILED, afterTimeout.status());
        assertNotNull(afterTimeout.error());
        assertTrue(afterTimeout.error().toLowerCase().contains("tiempo") || !afterTimeout.error().isBlank());
    }

    @Test
    void entradaTerminalMasViejaQueElGracePeriod_arrancaTaskIdNuevo() throws InterruptedException {
        LongRunningTaskRegistry registry = newRegistry(0); // grace period 0 -> vence apenas termina

        LongRunningTask first = registry.getOrStart("key-grace", Duration.ofMinutes(1), 5000L,
                Duration.ofSeconds(2), () -> "primer-resultado");
        assertEquals(TaskStatus.COMPLETED, first.status());

        Thread.sleep(50); // asegura que "ahora" ya esta despues de lastUpdatedAt + 0s de gracia

        AtomicInteger calls = new AtomicInteger();
        LongRunningTask second = registry.getOrStart("key-grace", Duration.ofMinutes(1), 5000L,
                Duration.ofSeconds(2), () -> {
                    calls.incrementAndGet();
                    return "segundo-resultado";
                });

        assertEquals(1, calls.get(), "debe arrancar un job nuevo, no reusar el resultado viejo");
        assertEquals(TaskStatus.COMPLETED, second.status());
        assertEquals("segundo-resultado", second.result());
        assertNotEquals(first.taskId(), second.taskId());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
