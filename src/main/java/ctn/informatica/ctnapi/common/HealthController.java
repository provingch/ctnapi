package ctn.informatica.ctnapi.common;

import java.sql.Connection;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Chequeo de salud para nginx / el monitor: responde 200 si la base contesta y 503 si no. Sin datos sensibles. */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try (Connection c = new conexion().getCon()) {
            boolean viva = c.isValid(2);
            return ResponseEntity.status(viva ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", viva, "estado", viva ? "up" : "db-down"));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("ok", false, "estado", "db-down"));
        }
    }
}
