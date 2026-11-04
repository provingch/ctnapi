package ctn.informatica.ctnapi.biblioteca;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** Errores de {@link BibliotecaController} en el formato {@code {ok: false, error}}, sin detalles internos. */
@RestControllerAdvice(assignableTypes = BibliotecaController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BibliotecaErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(BibliotecaErrorHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> conEstado(ResponseStatusException ex) {
        return error(ex.getStatusCode().value(), ex.getReason());
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> parametroInvalido(Exception ex) {
        return error(HttpStatus.BAD_REQUEST.value(), "Uno o más parámetros no son válidos");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> interno(Exception ex) {
        log.error("Error en la API de biblioteca", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Error interno del servidor");
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String mensaje) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", mensaje == null ? "Error" : mensaje));
    }
}
