package com.atina.jdeMCPServer.gateway;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Evita mandar llamadas duplicadas al backend de JDE (Mulesoft / Gateway de Atina)
 * cuando el mismo pedido está en curso al mismo tiempo -- el caso típico es un
 * cliente MCP que, tras su propio timeout (~60s), cancela y reintenta la misma
 * tool call mientras la llamada anterior sigue bloqueada esperando a JDE (que
 * puede tardar mucho más). Sin esto, cada reintento dispara una llamada nueva a
 * Atina/Mulesoft encima de las anteriores, que siguen corriendo igual.
 *
 * El primer caller para una clave dada ejecuta la llamada real en su propio
 * thread (no se usa un executor propio); cualquier otro caller concurrente con
 * la MISMA clave espera y recibe el mismo resultado (o la misma excepción) en
 * vez de disparar una llamada nueva. Una vez terminada, la clave se libera: no
 * es una cache de resultados, solo evita duplicar llamadas superpuestas.
 */
@Component
public class RequestCoalescer {

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T execute(String key, Supplier<T> call) {
        CompletableFuture<Object> ownFuture = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, ownFuture);
        if (existing != null) {
            return (T) awaitResult(existing);
        }

        try {
            T result = call.get();
            ownFuture.complete(result);
            return result;
        } catch (RuntimeException e) {
            ownFuture.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, ownFuture);
        }
    }

    private Object awaitResult(CompletableFuture<Object> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }
}
