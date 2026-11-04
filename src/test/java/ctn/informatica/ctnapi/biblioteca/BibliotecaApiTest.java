package ctn.informatica.ctnapi.biblioteca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** API de biblioteca contra el contexto real de Spring, con el DAO mockeado. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"biblioteca.api-key=clave-biblioteca-test", "ctnapi.core-api-key=clave-core-test",
        "anotaciones.api-key=clave-anotaciones-test", "enfermeria.api-key=clave-enfermeria-test"})
class BibliotecaApiTest {

    private static final String KEY = "clave-biblioteca-test";
    private static final String ALUMNOS = "/api/biblioteca/v1/alumnos";
    private static final String PROFESORES = "/api/biblioteca/v1/profesores";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BibliotecaDao dao;

    private static Map<String, Object> alumno(String curso) {
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("id", 1842);
        fila.put("ci", "5123456");
        fila.put("nombre", "María");
        fila.put("apellido", "González");
        fila.put("curso", curso);
        fila.put("vigente", curso != null);
        return fila;
    }

    private static Map<String, Object> profesor() {
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("id", 317);
        fila.put("ci", "3456789");
        fila.put("nombre", "Carlos");
        fila.put("apellido", "Benítez");
        fila.put("especialidad", null);
        return fila;
    }

    @Test
    void sinClaveOConClaveIncorrectaDevuelve401() throws Exception {
        mockMvc.perform(get(ALUMNOS).param("ci", "123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Clave de la app inválida o ausente"));
        mockMvc.perform(get(ALUMNOS).param("ci", "123").header("X-API-Key", "otra"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(dao);
    }

    @Test
    void lasClavesDeOtrasApisNoSonIntercambiables() throws Exception {
        mockMvc.perform(get(ALUMNOS).param("ci", "123").header("X-API-Key", "clave-core-test"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(ALUMNOS).param("ci", "123").header("X-Service-Key", "clave-core-test"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/core/v1/especialidades").header("X-Service-Key", KEY))
                .andExpect(status().isUnauthorized());
        for (String otraClave : List.of("clave-anotaciones-test", "clave-enfermeria-test")) {
            mockMvc.perform(get(ALUMNOS).param("ci", "123").header("X-API-Key", otraClave))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/anotaciones/tipos_anotacion").header("X-API-Key", KEY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/enfermeria/motivos").header("X-API-Key", KEY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(dao);
    }

    @Test
    void esDeSoloLectura() throws Exception {
        mockMvc.perform(post(ALUMNOS).header("X-API-Key", KEY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.ok").value(false));
        mockMvc.perform(put(PROFESORES + "/1").header("X-API-Key", KEY)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(PROFESORES + "/1").header("X-API-Key", KEY)).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(dao);
    }

    @Test
    void buscaAlumnoPorCiNormalizada() throws Exception {
        when(dao.alumnosPorCi(eq("5123456"), anyInt())).thenReturn(List.of(alumno("2° A")));

        mockMvc.perform(get(ALUMNOS).header("X-API-Key", KEY).param("ci", " 5.123.456 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.datos[0].id").value(1842))
                .andExpect(jsonPath("$.datos[0].ci").value("5123456"))
                .andExpect(jsonPath("$.datos[0].curso").value("2° A"))
                .andExpect(jsonPath("$.datos[0].vigente").value(true));
    }

    @Test
    void ciSinCoincidenciasDevuelveListaVacia() throws Exception {
        when(dao.alumnosPorCi(eq("999"), anyInt())).thenReturn(List.of());

        mockMvc.perform(get(ALUMNOS).header("X-API-Key", KEY).param("ci", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datos").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void buscaAlumnoPorPalabrasYPagina() throws Exception {
        when(dao.buscarAlumnos(eq(List.of("gonz", "maria")), eq(2), eq(10), anyInt()))
                .thenReturn(List.of(alumno("2° A")));
        when(dao.contarAlumnos(List.of("gonz", "maria"))).thenReturn(11);

        mockMvc.perform(get(ALUMNOS).header("X-API-Key", KEY).param("q", "  gonz   maria ")
                        .param("pagina", "2").param("limite", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.datos[0].nombre").value("María"));
    }

    @Test
    void usaPaginacionPorDefecto() throws Exception {
        when(dao.buscarAlumnos(anyList(), eq(1), eq(20), anyInt())).thenReturn(List.of());

        mockMvc.perform(get(ALUMNOS).header("X-API-Key", KEY).param("q", "ana")).andExpect(status().isOk());
        verify(dao).buscarAlumnos(eq(List.of("ana")), eq(1), eq(20), anyInt());
    }

    @Test
    void egresadoSeDevuelveSinCursoYNoVigente() throws Exception {
        when(dao.alumno(eq(1842), anyInt())).thenReturn(alumno(null));

        mockMvc.perform(get(ALUMNOS + "/1842").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datos.curso").isEmpty())
                .andExpect(jsonPath("$.datos.vigente").value(false));
    }

    @Test
    void alumnoInexistenteDevuelve404() throws Exception {
        when(dao.alumno(eq(9), anyInt())).thenReturn(null);

        mockMvc.perform(get(ALUMNOS + "/9").header("X-API-Key", KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("No existe el alumno"));
    }

    @Test
    void buscaProfesorPorCiYPorPalabras() throws Exception {
        when(dao.profesoresPorCi("3456789")).thenReturn(List.of(profesor()));
        when(dao.buscarProfesores(List.of("ben"), 1, 20)).thenReturn(List.of(profesor()));
        when(dao.contarProfesores(List.of("ben"))).thenReturn(1);

        mockMvc.perform(get(PROFESORES).header("X-API-Key", KEY).param("ci", "3.456.789"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datos[0].id").value(317))
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get(PROFESORES).header("X-API-Key", KEY).param("q", "ben"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datos[0].apellido").value("Benítez"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void profesorPorIdY404() throws Exception {
        when(dao.profesor(317)).thenReturn(profesor());
        when(dao.profesor(9)).thenReturn(null);

        mockMvc.perform(get(PROFESORES + "/317").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datos.nombre").value("Carlos"));
        mockMvc.perform(get(PROFESORES + "/9").header("X-API-Key", KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No existe el profesor"));
    }

    @Test
    void rechazaCriteriosInvalidosSinConsultarLaBase() throws Exception {
        for (String ruta : List.of(ALUMNOS, PROFESORES)) {
            mockMvc.perform(get(ruta).header("X-API-Key", KEY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false));
            mockMvc.perform(get(ruta).header("X-API-Key", KEY).param("ci", "1").param("q", "ana"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(ruta).header("X-API-Key", KEY).param("ci", "12a"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(ruta).header("X-API-Key", KEY).param("ci", "1".repeat(21)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(ruta).header("X-API-Key", KEY).param("q", "a"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(ruta).header("X-API-Key", KEY).param("q", "x".repeat(91)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(ruta).header("X-API-Key", KEY).param("q", "a b c d e f g"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(dao);
    }

    @Test
    void rechazaPaginacionEIdsInvalidos() throws Exception {
        for (String ruta : List.of(ALUMNOS, PROFESORES)) {
            mockMvc.perform(get(ruta).header("X-API-Key", KEY).param("q", "ana").param("pagina", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Uno o más parámetros no son válidos"));
            mockMvc.perform(get(ruta).header("X-API-Key", KEY).param("q", "ana").param("pagina", "100001"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(ruta).header("X-API-Key", KEY).param("q", "ana").param("limite", "51"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(ruta).header("X-API-Key", KEY).param("q", "ana").param("limite", "abc"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(ruta + "/0").header("X-API-Key", KEY)).andExpect(status().isBadRequest());
            mockMvc.perform(get(ruta + "/abc").header("X-API-Key", KEY)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(dao);
    }

    @Test
    void unErrorDeBaseNoExponeDetalles() throws Exception {
        when(dao.alumnosPorCi(anyString(), anyInt())).thenThrow(new java.sql.SQLException("detalle interno de la base"));

        mockMvc.perform(get(ALUMNOS).header("X-API-Key", KEY).param("ci", "123"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Error interno del servidor"));
        verify(dao, never()).contarAlumnos(any());
    }

    @Test
    void normalizaCiYSeparaPalabras() {
        assertEquals("1234567", BibliotecaController.normalizarCi(" 1.234-567 "));
        assertEquals(List.of("juan", "perez"), BibliotecaController.palabras(" juan   perez "));
    }
}
