package com.atina.jdeMCPServer.logs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Busca, en el archivo de log activo (logging.file.name), las lineas de un
 * correlation ID -- pensado para que soporte/ops pueda diagnosticar un
 * tools/call fallido (ver CorrelationIdContext) sin acceso al filesystem del
 * servidor. Protegido con el mismo JWT (Keycloak/Atina) que /mcp, ver
 * SecurityConfig -- sin eso quedaria publico por el catch-all
 * anyRequest().permitAll().
 *
 * Solo busca en el archivo activo, no en los rotados/comprimidos
 * (logging.logback.rollingpolicy.*, *.gz): alcanza para diagnosticar fallas
 * recientes, que es el caso de uso real.
 */
@RestController
public class LogSearchController {

    private final Path logFilePath;

    public LogSearchController(@Value("${logging.file.name}") String logFilePath) {
        this.logFilePath = Path.of(logFilePath);
    }

    @GetMapping("/admin/logs")
    public ResponseEntity<LogSearchResult> search(@RequestParam String correlationId) {
        if (correlationId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (!Files.exists(logFilePath)) {
            return ResponseEntity.ok(new LogSearchResult(correlationId, false, 0, List.of()));
        }

        String marker = "[correlationId=" + correlationId + "]";
        try (Stream<String> lines = Files.lines(logFilePath)) {
            List<String> matches = lines.filter(line -> line.contains(marker)).toList();
            return ResponseEntity.ok(new LogSearchResult(correlationId, !matches.isEmpty(), matches.size(), matches));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo de log: " + logFilePath, e);
        }
    }
}
