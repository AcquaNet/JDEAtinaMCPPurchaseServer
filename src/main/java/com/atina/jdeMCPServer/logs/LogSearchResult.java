package com.atina.jdeMCPServer.logs;

import java.util.List;

/**
 * Resultado de buscar un correlation ID en el archivo de log activo
 * (logging.file.name) -- ver LogSearchController.
 */
public record LogSearchResult(String correlationId, boolean found, int lineCount, List<String> lines) {
}
