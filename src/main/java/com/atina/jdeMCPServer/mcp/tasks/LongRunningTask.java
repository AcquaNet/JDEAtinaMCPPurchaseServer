package com.atina.jdeMCPServer.mcp.tasks;

import java.time.Instant;

/**
 * Snapshot inmutable del estado de una tarea larga administrada por
 * {@link LongRunningTaskRegistry}. Mismos campos que el Task real de
 * SEP-2663 (forma plana -- taskId/status/etc. directos, no anidados bajo una
 * key "task"), para que migrar a la extension real del protocolo el dia que
 * el SDK/los clientes la soporten sea reemplazar el adaptador de exposicion,
 * no este modelo.
 */
public record LongRunningTask(
        String taskId,
        TaskStatus status,
        String statusMessage,
        Instant createdAt,
        Instant lastUpdatedAt,
        Long ttlMs,
        Long pollIntervalMs,
        Object result,
        String error
) {

    public boolean isTerminal() {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED;
    }

    public static LongRunningTask working(String taskId, Long ttlMs, Long pollIntervalMs) {
        Instant now = Instant.now();
        return new LongRunningTask(taskId, TaskStatus.WORKING,
                "La operación está en curso.", now, now, ttlMs, pollIntervalMs, null, null);
    }

    public LongRunningTask withCompleted(Object result) {
        return new LongRunningTask(taskId, TaskStatus.COMPLETED,
                "Listo.", createdAt, Instant.now(), ttlMs, pollIntervalMs, result, null);
    }

    public LongRunningTask withFailed(String error) {
        return new LongRunningTask(taskId, TaskStatus.FAILED,
                error, createdAt, Instant.now(), ttlMs, pollIntervalMs, null, error);
    }
}
