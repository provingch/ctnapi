package ctn.informatica.ctnapi.core;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice(assignableTypes = CoreController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CoreErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(CoreErrorHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> conEstado(ResponseStatusException ex) {
        return error(ex.getStatusCode().value(), ex.getReason());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> cuerpoInvalido(MethodArgumentNotValidException ex) {
        FieldError field = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = field == null ? "El cuerpo de la solicitud no es válido"
                : "El campo '" + field.getField() + "' no es válido";
        return error(HttpStatus.BAD_REQUEST.value(), message);
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> parametroInvalido(Exception ex) {
        return error(HttpStatus.BAD_REQUEST.value(), "Uno o más parámetros no son válidos");
    }

    @ExceptionHandler(SQLIntegrityConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> conflictoIntegridad(SQLIntegrityConstraintViolationException ex) {
        log.info("Conflicto de integridad en API core: {}", ex.getMessage());
        return error(HttpStatus.CONFLICT.value(), "La operación entra en conflicto con datos existentes o relacionados");
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> errorBase(SQLException ex) {
        if ("23000".equals(ex.getSQLState())) {
            log.info("Conflicto de integridad en API core: {}", ex.getMessage());
            return error(HttpStatus.CONFLICT.value(), "La operación entra en conflicto con datos existentes o relacionados");
        }
        log.error("Error de base de datos en API core", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Error interno del servidor");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> interno(Exception ex) {
        log.error("Error en API core", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Error interno del servidor");
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", message == null ? "Error" : message));
    }
}
