package com.atina.jdeMCPServer.purchase.model;

/**
 * Estado de una respuesta estructurada de tool. Compartido entre las tools de
 * purchase order aunque no todas emitan todos los valores (ej. solo
 * jde_list_pending_purchase_orders usa IN_PROGRESS/CANCELLED, por estar
 * conectada al LongRunningTaskRegistry; solo approve/reject usan UNAUTHORIZED).
 */
public enum ToolStatus {
    OK,
    INVALID_REQUEST,
    UNAUTHORIZED,
    IN_PROGRESS,
    FAILED,
    CANCELLED
}
