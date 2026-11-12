package ctn.informatica.ctnapi.core;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/core/v1")
@Validated
public class CoreController {

    /** Paraguay: UTC-3 todo el año (igual que la API de biblioteca). */
    private static final ZoneOffset PARAGUAY = ZoneOffset.ofHours(-3);

    public record PerfilRequest(
            @Size(max = 45) String nombre,
            @Size(max = 45) String apellido,
            @Min(0) @Max(2_147_483_647) Integer ci,
            @Size(max = 45) String telefono,
            @Size(max = 45) String celular,
            @Size(max = 255) String correo,
            @Min(0) @Max(127) int nivel,
            @Min(1) Integer especialidadId) {
    }

    public record AlumnoRequest(
            @Size(max = 20) String ci,
            @NotBlank @Size(max = 45) String nombre,
            @NotBlank @Size(max = 45) String apellido,
            @Min(1) int cursoId,
            @Min(1) Integer actorId,
            @Size(max = 255) String email,
            @Size(max = 45) String telefono) {
    }

    public record CursoRequest(
            @Min(1) int especialidadId,
            @Min(1900) @Max(2200) int promocion,
            @NotBlank @Pattern(regexp = "[ABC]") String seccion) {
    }

    public record CursoBaseRequest(
            @Min(1) int especialidadId,
            @Min(1) @Max(6) int nivel,
            @NotBlank @Pattern(regexp = "[ABC]") String seccion) {
    }

    public record MateriaRequest(
            @NotBlank @Size(max = 45) String nombre,
            @NotBlank @Pattern(regexp = "comun|especifico") String categoria,
            @NotNull List<@NotNull @Min(1) Integer> especialidadIds) {
    }

    public record AsignacionRequest(
            @Min(1) int profesorId,
            @Min(1) int materiaId,
            @Min(1) int cursoBaseId) {
    }

    public record VinculoRequest(
            @Min(1) int perfilId,
            @Size(max = 45) @Pattern(regexp = ".*\\S.*") String parentesco) {
    }

    public record CodigoConductaRequest(
            @NotBlank @Pattern(regexp = "N[A-Za-z0-9]{0,9}") String codigo,
            @NotBlank @Size(max = 255) String descripcion) {
    }

    private final CoreDao dao;

    public CoreController(CoreDao dao) {
        this.dao = dao;
    }

    @GetMapping("/especialidades")
    public Map<String, Object> especialidades() throws SQLException {
        return ok(dao.especialidades());
    }

    @GetMapping("/perfiles")
    public Map<String, Object> perfiles(@RequestParam(required = false) Integer nivel) throws SQLException {
        return ok(dao.perfiles(nivel));
    }

    @GetMapping("/perfiles/{id}")
    public Map<String, Object> perfil(@PathVariable @Min(1) int id) throws SQLException {
        return ok(requerir(dao.perfil(id), "No existe el perfil"));
    }

    @PostMapping("/perfiles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crearPerfil(@Valid @RequestBody PerfilRequest perfil) throws SQLException {
        return ok(Map.of("id", dao.crearPerfil(perfil)));
    }

    @PutMapping("/perfiles/{id}")
    public Map<String, Object> actualizarPerfil(@PathVariable @Min(1) int id, @Valid @RequestBody PerfilRequest perfil)
            throws SQLException {
        if (!dao.actualizarPerfil(id, perfil)) throw noExiste("No existe el perfil");
        return ok(Map.of("id", id));
    }

    @DeleteMapping("/perfiles/{id}")
    public Map<String, Object> eliminarPerfil(@PathVariable @Min(1) int id) throws SQLException {
        if (!dao.eliminarPerfil(id)) throw noExiste("No existe el perfil");
        return ok(Map.of("id", id));
    }

    @GetMapping("/alumnos")
    public Map<String, Object> alumnos(@RequestParam(required = false) @Min(1) Integer cursoId,
            @RequestParam(required = false) @Min(1) Integer especialidadId) throws SQLException {
        return ok(dao.alumnos(cursoId, especialidadId));
    }

    @GetMapping("/alumnos/{id}")
    public Map<String, Object> alumno(@PathVariable @Min(1) int id) throws SQLException {
        return ok(requerir(dao.alumno(id), "No existe el alumno"));
    }

    @GetMapping("/alumnos/{id}/historial-cursos")
    public Map<String, Object> historialCursos(@PathVariable @Min(1) int id) throws SQLException {
        requerir(dao.alumno(id), "No existe el alumno");
        return ok(dao.historialCursos(id));
    }

    @PostMapping("/alumnos")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crearAlumno(@Valid @RequestBody AlumnoRequest alumno) throws SQLException {
        return ok(Map.of("id", dao.crearAlumno(alumno)));
    }

    @PutMapping("/alumnos/{id}")
    public Map<String, Object> actualizarAlumno(@PathVariable @Min(1) int id, @Valid @RequestBody AlumnoRequest alumno)
            throws SQLException {
        if (!dao.actualizarAlumno(id, alumno, alumno.actorId())) throw noExiste("No existe el alumno");
        return ok(Map.of("id", id));
    }

    @DeleteMapping("/alumnos/{id}")
    public Map<String, Object> eliminarAlumno(@PathVariable @Min(1) int id) throws SQLException {
        if (!dao.eliminarAlumno(id)) throw noExiste("No existe el alumno");
        return ok(Map.of("id", id));
    }

    @GetMapping("/cursos")
    public Map<String, Object> cursos(@RequestParam(required = false) @Min(1) Integer especialidadId,
            @RequestParam(required = false) @Min(1900) @Max(2200) Integer promocion) throws SQLException {
        return ok(dao.cursos(especialidadId, promocion));
    }

    @PostMapping("/cursos")
    public Map<String, Object> asegurarCurso(@Valid @RequestBody CursoRequest curso) throws SQLException {
        return ok(Map.of("id", dao.asegurarCurso(curso)));
    }

    @GetMapping("/cursos-base")
    public Map<String, Object> cursosBase(@RequestParam(required = false) @Min(1) Integer especialidadId)
            throws SQLException {
        return ok(dao.cursosBase(especialidadId));
    }

    @PostMapping("/cursos-base")
    public Map<String, Object> asegurarCursoBase(@Valid @RequestBody CursoBaseRequest curso) throws SQLException {
        return ok(Map.of("id", dao.asegurarCursoBase(curso)));
    }

    @GetMapping("/materias")
    public Map<String, Object> materias() throws SQLException {
        return ok(dao.materias());
    }

    @PostMapping("/materias")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crearMateria(@Valid @RequestBody MateriaRequest materia) throws SQLException {
        return ok(Map.of("id", dao.crearMateria(materia)));
    }

    @PutMapping("/materias/{id}")
    public Map<String, Object> actualizarMateria(@PathVariable @Min(1) int id, @Valid @RequestBody MateriaRequest materia)
            throws SQLException {
        if (!dao.actualizarMateria(id, materia)) throw noExiste("No existe la materia");
        return ok(Map.of("id", id));
    }

    @DeleteMapping("/materias/{id}")
    public Map<String, Object> eliminarMateria(@PathVariable @Min(1) int id) throws SQLException {
        if (!dao.eliminarMateria(id)) throw noExiste("No existe la materia");
        return ok(Map.of("id", id));
    }

    @GetMapping("/asignaciones")
    public Map<String, Object> asignaciones(@RequestParam(required = false) @Min(1) Integer profesorId,
            @RequestParam(required = false) @Min(1) Integer especialidadId) throws SQLException {
        return ok(dao.asignaciones(profesorId, especialidadId));
    }

    @PostMapping("/asignaciones")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crearAsignacion(@Valid @RequestBody AsignacionRequest asignacion) throws SQLException {
        return ok(Map.of("id", dao.crearAsignacion(asignacion)));
    }

    @PutMapping("/asignaciones/{id}")
    public Map<String, Object> actualizarAsignacion(@PathVariable @Min(1) int id,
            @Valid @RequestBody AsignacionRequest asignacion) throws SQLException {
        if (!dao.actualizarAsignacion(id, asignacion)) throw noExiste("No existe la asignación");
        return ok(Map.of("id", id));
    }

    @DeleteMapping("/asignaciones/{id}")
    public Map<String, Object> eliminarAsignacion(@PathVariable @Min(1) int id) throws SQLException {
        if (!dao.eliminarAsignacion(id)) throw noExiste("No existe la asignación");
        return ok(Map.of("id", id));
    }

    @GetMapping("/alumnos/{id}/familia")
    public Map<String, Object> familia(@PathVariable @Min(1) int id) throws SQLException {
        return ok(dao.vinculosFamilia(id));
    }

    @PostMapping("/alumnos/{id}/familia")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> vincularFamilia(@PathVariable @Min(1) int id, @Valid @RequestBody VinculoRequest vinculo)
            throws SQLException {
        dao.vincularFamilia(id, vinculo.perfilId(),
                vinculo.parentesco() == null ? "padre" : vinculo.parentesco());
        return ok(Map.of("alumnoId", id, "perfilId", vinculo.perfilId()));
    }

    @DeleteMapping("/alumnos/{id}/familia/{perfilId}")
    public Map<String, Object> desvincularFamilia(@PathVariable @Min(1) int id, @PathVariable @Min(1) int perfilId)
            throws SQLException {
        if (!dao.desvincularFamilia(id, perfilId)) throw noExiste("No existe el vínculo familiar");
        return ok(Map.of("alumnoId", id, "perfilId", perfilId));
    }

    /**
     * Cursos de un profesor (solo cursos, sin materias), por la promoción del año: {@code anio} opcional, por
     * defecto el año actual en Paraguay. Un perfil que no es profesor o no tiene asignaciones devuelve lista vacía.
     */
    @GetMapping("/perfiles/{id}/cursos")
    public Map<String, Object> cursosDelProfesor(@PathVariable @Min(1) int id,
            @RequestParam(required = false) @Min(1900) @Max(2200) Integer anio) throws SQLException {
        requerir(dao.perfil(id), "No existe el perfil");
        int anioEfectivo = anio != null ? anio : LocalDate.now(PARAGUAY).getYear();
        return ok(dao.cursosDeProfesor(id, anioEfectivo));
    }

    @GetMapping("/perfiles/{id}/alumnos")
    public Map<String, Object> hijos(@PathVariable @Min(1) int id) throws SQLException {
        return ok(dao.hijos(id));
    }

    @GetMapping("/codigos-conducta")
    public Map<String, Object> codigosConducta(@RequestParam(defaultValue = "false") boolean incluirInactivos)
            throws SQLException {
        return ok(dao.codigosConducta(incluirInactivos));
    }

    @PostMapping("/codigos-conducta")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crearCodigoConducta(@Valid @RequestBody CodigoConductaRequest codigo) throws SQLException {
        return ok(Map.of("id", dao.crearCodigoConducta(codigo)));
    }

    @PutMapping("/codigos-conducta/{id}")
    public Map<String, Object> actualizarCodigoConducta(@PathVariable @Min(1) int id,
            @Valid @RequestBody CodigoConductaRequest codigo) throws SQLException {
        if (!dao.actualizarCodigoConducta(id, codigo)) throw noExiste("No existe el código de conducta");
        return ok(Map.of("id", id));
    }

    @PutMapping("/codigos-conducta/{id}/activo")
    public Map<String, Object> activarCodigoConducta(@PathVariable @Min(1) int id, @RequestParam boolean activo)
            throws SQLException {
        if (!dao.activarCodigoConducta(id, activo)) throw noExiste("No existe el código de conducta");
        return ok(Map.of("id", id, "activo", activo));
    }

    private static <T> T requerir(T value, String message) {
        if (value == null) throw noExiste(message);
        return value;
    }

    private static ResponseStatusException noExiste(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static Map<String, Object> ok(Object datos) {
        return Map.of("ok", true, "datos", datos);
    }
}
