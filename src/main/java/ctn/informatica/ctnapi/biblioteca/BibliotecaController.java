package ctn.informatica.ctnapi.biblioteca;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Consulta de alumnos y profesores para registrar préstamos en el sistema de biblioteca.
 * Solo lectura y solo datos de identificación: las búsquedas por CI son exactas para que no se pueda
 * recorrer el padrón con búsquedas parciales.
 */
@RestController
@RequestMapping("/api/biblioteca/v1")
@Validated
public class BibliotecaController {

    /** Paraguay: UTC-3 fijo. El curso no depende de la zona del servidor. */
    private static final ZoneOffset PARAGUAY = ZoneOffset.ofHours(-3);
    private static final int MAX_PAGINA = 100_000;
    private static final int MAX_LIMITE = 50;
    private static final int MIN_BUSQUEDA = 2;
    private static final int MAX_BUSQUEDA = 90;
    private static final int MAX_PALABRAS = 6;

    private final BibliotecaDao dao;

    public BibliotecaController(BibliotecaDao dao) {
        this.dao = dao;
    }

    @GetMapping("/alumnos")
    public Map<String, Object> alumnos(@RequestParam(required = false) String ci,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") @Min(1) @Max(MAX_PAGINA) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_LIMITE) int limite) throws SQLException {
        validarCriterio(ci, q);
        if (ci != null) {
            List<Map<String, Object>> datos = dao.alumnosPorCi(normalizarCi(ci), anioActual());
            return lista(datos, datos.size());
        }
        List<String> palabras = palabras(q);
        return lista(dao.buscarAlumnos(palabras, pagina, limite, anioActual()), dao.contarAlumnos(palabras));
    }

    @GetMapping("/alumnos/{id}")
    public Map<String, Object> alumno(@PathVariable @Min(1) int id) throws SQLException {
        return ok(requerir(dao.alumno(id, anioActual()), "No existe el alumno"));
    }

    @GetMapping("/profesores")
    public Map<String, Object> profesores(@RequestParam(required = false) String ci,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") @Min(1) @Max(MAX_PAGINA) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_LIMITE) int limite) throws SQLException {
        validarCriterio(ci, q);
        if (ci != null) {
            // usuario.ci no es única: puede haber más de un perfil con la misma CI.
            List<Map<String, Object>> datos = dao.profesoresPorCi(normalizarCi(ci));
            return lista(datos, datos.size());
        }
        List<String> palabras = palabras(q);
        return lista(dao.buscarProfesores(palabras, pagina, limite), dao.contarProfesores(palabras));
    }

    @GetMapping("/profesores/{id}")
    public Map<String, Object> profesor(@PathVariable @Min(1) int id) throws SQLException {
        return ok(requerir(dao.profesor(id), "No existe el profesor"));
    }

    private static void validarCriterio(String ci, String q) {
        if ((ci == null) == (q == null)) {
            throw invalido("Indicá 'ci' o 'q', uno de los dos");
        }
    }

    /** Quita puntos y guiones, así "1.234.567" y "1234567" son la misma CI. */
    static String normalizarCi(String ci) {
        String limpia = ci.trim().replace(".", "").replace("-", "");
        if (!limpia.matches("[0-9]{1,20}")) throw invalido("La CI debe contener solo números");
        return limpia;
    }

    /** Separa la búsqueda en palabras: todas deben coincidir, en cualquier orden. */
    static List<String> palabras(String q) {
        String texto = q.trim();
        if (texto.length() < MIN_BUSQUEDA || texto.length() > MAX_BUSQUEDA) {
            throw invalido("La búsqueda debe tener entre " + MIN_BUSQUEDA + " y " + MAX_BUSQUEDA + " caracteres");
        }
        List<String> palabras = Arrays.asList(texto.split("\\s+"));
        if (palabras.size() > MAX_PALABRAS) {
            throw invalido("La búsqueda admite hasta " + MAX_PALABRAS + " palabras");
        }
        return palabras;
    }

    private static int anioActual() {
        return LocalDate.now(PARAGUAY).getYear();
    }

    private static <T> T requerir(T valor, String mensaje) {
        if (valor == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, mensaje);
        return valor;
    }

    private static ResponseStatusException invalido(String mensaje) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensaje);
    }

    private static Map<String, Object> ok(Object datos) {
        return Map.of("ok", true, "datos", datos);
    }

    private static Map<String, Object> lista(List<Map<String, Object>> datos, int total) {
        return Map.of("ok", true, "datos", datos, "total", total);
    }
}
