package com.atina.jdeMCPServer.salesorder.model;

/**
 * Salida estructurada de jde_get_customer_detail. pollAfterSeconds es 0 salvo
 * cuando status = IN_PROGRESS; customer nunca es null -- usa
 * CustomerDetail.empty() cuando status != OK (el SDK de MCP valida
 * structuredContent contra outputSchema y rechaza null en campos no
 * declarados nullable).
 */
public record CustomerDetailResult(
        ToolStatus status,
        String message,
        Integer pollAfterSeconds,
        CustomerDetail customer
) {
}
