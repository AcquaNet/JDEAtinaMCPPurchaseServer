package com.atina.jdeMCPServer.salesorder.model;

/**
 * Estado de una respuesta estructurada de tool. Compartido entre varias tools
 * de sales order aunque no todas emitan todos los valores (ej. hoy solo
 * jde_search_items usa IN_PROGRESS/CANCELLED, por estar conectada al
 * LongRunningTaskRegistry).
 */
public enum ToolStatus {
    OK,
    INVALID_REQUEST,
    IN_PROGRESS,
    FAILED,
    CANCELLED
}
