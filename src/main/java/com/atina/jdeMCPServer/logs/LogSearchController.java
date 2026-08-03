package com.atina.jdeMCPServer.logs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private final AtinaLogCaptureClient atinaLogCaptureClient;

    public LogSearchController(@Value("${logging.file.name}") String logFilePath,
                                AtinaLogCaptureClient atinaLogCaptureClient) {
        this.logFilePath = Path.of(logFilePath);
        this.atinaLogCaptureClient = atinaLogCaptureClient;
    }

    @GetMapping("/admin/logs")
    public ResponseEntity<?> search(
            @RequestParam String correlationId,
            @RequestParam(defaultValue = "false") boolean includeMicroserviceLogs,
            @RequestParam(defaultValue = "false") boolean download) {
        if (correlationId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        List<String> lines = new ArrayList<>(searchLocalLog(correlationId));
        // Anexado en secuencia: primero el log del Gateway (microserviceLog=false),
        // despues el del microservicio conector JDE (microserviceLog=true) -- ver
        // AtinaLogCaptureClient. Nunca lanza: una falla en cualquiera de los dos
        // queda anotada inline, no rompe el resto de la respuesta.
        if (includeMicroserviceLogs) {
            lines.addAll(atinaLogCaptureClient.captureGatewayAndMicroserviceLogs(correlationId));
        }

        if (download) {
            String filename = correlationId.replaceAll("[\"\\r\\n]", "_") + ".log";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(String.join("\n", lines));
        }

        return ResponseEntity.ok(new LogSearchResult(correlationId, !lines.isEmpty(), lines.size(), lines));
    }

    private List<String> searchLocalLog(String correlationId) {
        if (!Files.exists(logFilePath)) {
            return List.of();
        }
        String marker = "[correlationId=" + correlationId + "]";
        try (Stream<String> fileLines = Files.lines(logFilePath)) {
            return fileLines.filter(line -> line.contains(marker)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo de log: " + logFilePath, e);
        }
    }
}
