package com.atina.jdeMCPServer.mcp.tasks;

/**
 * Mismos nombres de estado que el Task de SEP-2663 ("Tasks Extension" de MCP,
 * ver https://modelcontextprotocol.io/seps/2663-tasks-extension) -- para que
 * el dia que el SDK/los clientes MCP lo soporten, mapear este motor casero a
 * la extension real sea mecanico. INPUT_REQUIRED y CANCELLED no los usa
 * jde_list_pending_purchase_orders todavia (no hace falta elicitation
 * intermedia ni un tool de cancelacion), pero quedan modelados para paridad
 * futura con otros tools que sí los necesiten.
 */
public enum TaskStatus {
    WORKING,
    INPUT_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELLED
}
