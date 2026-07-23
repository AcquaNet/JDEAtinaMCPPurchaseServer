package com.atina.jdeMCPServer.mcp;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpMeta;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Envia "notifications/progress" del protocolo MCP durante llamadas lentas a JDE
 * (Mulesoft / Gateway de Atina), para que un cliente MCP que pidió seguimiento de
 * progreso (mandó "progressToken" en el _meta del tools/call) pueda extender su
 * propio timeout en vez de reportar la operación como colgada.
 *
 * Apagado por defecto (jde.mcp.progress-notifications.enabled=false): a la fecha
 * Claude Desktop no manda progressToken ni implementa resetTimeoutOnProgress (ver
 * https://github.com/anthropics/claude-code/issues/58687, cerrado "not planned"),
 * así que hoy esto no tiene ningún efecto para ese cliente -- queda listo para
 * activar el día que algún cliente MCP lo soporte, sin tener que tocar código.
 *
 * Si el cliente no pidió progreso (no mandó progressToken) esto es un no-op: no hay
 * token con el cual correlacionar la notificación del lado del cliente.
 */
@Component
public class McpProgressNotifications {

    private static final Logger log = LoggerFactory.getLogger(McpProgressNotifications.class);

    private static final String PROGRESS_TOKEN_KEY = "progressToken";

    private final boolean enabled;

    public McpProgressNotifications(
            @Value("${jde.mcp.progress-notifications.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        log.info("MCP progress notifications: {}", enabled ? "habilitadas" : "deshabilitadas (jde.mcp.progress-notifications.enabled=false)");
    }

    public void send(McpSyncServerExchange exchange, McpMeta meta, double progress, Double total, String message) {
        if (!enabled || exchange == null || meta == null) {
            return;
        }
        Object token = meta.get(PROGRESS_TOKEN_KEY);
        if (token == null) {
            // El cliente no pidió seguimiento de progreso en este tools/call (no mandó
            // _meta.progressToken): no hay nada que notificar. Se loguea para poder
            // diagnosticar timeouts del lado del cliente sin instrumentación ad-hoc.
            log.debug("Sin progressToken en la request: no se envía progress notification ('{}')", message);
            return;
        }
        try {
            exchange.progressNotification(new McpSchema.ProgressNotification(token.toString(), progress, total, message));
            log.debug("Progress notification enviada (token={}): {}", token, message);
        } catch (Exception e) {
            log.debug("No se pudo enviar la progress notification '{}': {}", message, e.getMessage());
        }
    }
}
