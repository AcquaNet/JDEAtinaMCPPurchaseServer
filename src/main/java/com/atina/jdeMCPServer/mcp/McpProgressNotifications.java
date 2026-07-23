package com.atina.jdeMCPServer.mcp;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpMeta;

/**
 * Envia "notifications/progress" del protocolo MCP durante llamadas lentas a JDE
 * (Mulesoft / Gateway de Atina), para que un cliente MCP que pidió seguimiento de
 * progreso (mandó "progressToken" en el _meta del tools/call) pueda extender su
 * propio timeout en vez de reportar la operación como colgada.
 *
 * Si el cliente no pidió progreso (no mandó progressToken) esto es un no-op: no hay
 * token con el cual correlacionar la notificación del lado del cliente.
 */
public final class McpProgressNotifications {

    private static final Logger log = LoggerFactory.getLogger(McpProgressNotifications.class);

    private static final String PROGRESS_TOKEN_KEY = "progressToken";

    private McpProgressNotifications() {
    }

    public static void send(McpSyncServerExchange exchange, McpMeta meta, double progress, Double total, String message) {
        if (exchange == null || meta == null) {
            return;
        }
        Object token = meta.get(PROGRESS_TOKEN_KEY);
        if (token == null) {
            return;
        }
        try {
            exchange.progressNotification(new McpSchema.ProgressNotification(token.toString(), progress, total, message));
        } catch (Exception e) {
            log.debug("No se pudo enviar la progress notification '{}': {}", message, e.getMessage());
        }
    }
}
