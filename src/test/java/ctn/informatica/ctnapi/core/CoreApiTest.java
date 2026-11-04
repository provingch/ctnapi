package ctn.informatica.ctnapi.core;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"ctnapi.core-api-key=clave-core-test", "ctnapi.core-write-enabled=true"})
class CoreApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CoreDao dao;

    @Test
    void requiereClaveDeServicioAntesDeAccederAlDao() throws Exception {
        mockMvc.perform(get("/api/core/v1/especialidades"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false));
        mockMvc.perform(get("/api/core/v1/especialidades").header("X-Service-Key", "otra-clave"))
                .andExpect(status().isUnauthorized());
        verify(dao, never()).especialidades();
    }

    @Test
    void devuelveEspecialidadesSoloConClaveDeServicio() throws Exception {
        when(dao.especialidades()).thenReturn(List.of(Map.of("id", 1, "nombre", "Informática")));

        mockMvc.perform(get("/api/core/v1/especialidades").header("X-Service-Key", "clave-core-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.datos[0].id").value(1))
                .andExpect(jsonPath("$.datos[0].nombre").value("Informática"));
    }

    @Test
    void cursosDelProfesorUsanElAnioActualPorDefecto() throws Exception {
        int anio = LocalDate.now(ZoneOffset.ofHours(-3)).getYear();
        when(dao.perfil(5)).thenReturn(Map.of("id", 5));
        when(dao.cursosDeProfesor(5, anio)).thenReturn(List.of(
                Map.of("curso_base_id", 12, "nivel", 3, "seccion", "A", "promocion", anio)));

        mockMvc.perform(get("/api/core/v1/perfiles/5/cursos").header("X-Service-Key", "clave-core-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.datos[0].curso_base_id").value(12))
                .andExpect(jsonPath("$.datos[0].promocion").value(anio));
        verify(dao).cursosDeProfesor(5, anio);
    }

    @Test
    void cursosDelProfesorAceptanAnioExplicito() throws Exception {
        when(dao.perfil(5)).thenReturn(Map.of("id", 5));
        when(dao.cursosDeProfesor(5, 2027)).thenReturn(List.of());

        mockMvc.perform(get("/api/core/v1/perfiles/5/cursos").param("anio", "2027")
                        .header("X-Service-Key", "clave-core-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datos").isEmpty());
        verify(dao).cursosDeProfesor(5, 2027);
    }

    @Test
    void cursosDelProfesorDa404SiElPerfilNoExisteYNoConsultaCursos() throws Exception {
        when(dao.perfil(99)).thenReturn(null);

        mockMvc.perform(get("/api/core/v1/perfiles/99/cursos").header("X-Service-Key", "clave-core-test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false));
        verify(dao, never()).cursosDeProfesor(anyInt(), anyInt());
    }

    @Test
    void cursosDelProfesorRequierenClaveDeServicio() throws Exception {
        mockMvc.perform(get("/api/core/v1/perfiles/5/cursos"))
                .andExpect(status().isUnauthorized());
        verify(dao, never()).cursosDeProfesor(anyInt(), anyInt());
    }

    @Test
    void creaPerfilYNoExponeCamposDeAutenticacion() throws Exception {
        when(dao.crearPerfil(org.mockito.ArgumentMatchers.any())).thenReturn(27);

        mockMvc.perform(post("/api/core/v1/perfiles")
                        .header("X-Service-Key", "clave-core-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Ana","apellido":"Docente","ci":1234567,"nivel":1,"especialidadId":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.datos.id").value(27))
                .andExpect(jsonPath("$.datos.contrasenia").doesNotExist());
    }

    @Test
    void validaDatosDeAlumnoAntesDeEscribir() throws Exception {
        mockMvc.perform(post("/api/core/v1/alumnos")
                        .header("X-Service-Key", "clave-core-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ci":"123","nombre":"","apellido":"Pérez","cursoId":3}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
        verify(dao, never()).crearAlumno(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unCambioDeCursoAceptaActorParaElHistorial() throws Exception {
        when(dao.actualizarAlumno(org.mockito.ArgumentMatchers.eq(8), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(4))).thenReturn(true);

        mockMvc.perform(put("/api/core/v1/alumnos/8")
                        .header("X-Service-Key", "clave-core-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ci":"234","nombre":"Luis","apellido":"Gómez","cursoId":5,"actorId":4}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datos.id").value(8));
        verify(dao).actualizarAlumno(org.mockito.ArgumentMatchers.eq(8), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(4));
    }
}
