package com.atina.jdeMCPServer.salesorder.model;

import java.util.List;

/**
 * Salida estructurada de jde_lookup_customer_by_name. pollAfterSeconds es 0
 * salvo cuando status = IN_PROGRESS; customers es una lista vacia salvo
 * cuando status = OK (nunca null -- el SDK de MCP valida structuredContent
 * contra outputSchema y rechaza null en campos no declarados nullable).
 */
public record CustomerLookupResult(
        ToolStatus status,
        String message,
        Integer pollAfterSeconds,
        List<CustomerSummary> customers
) {
}
