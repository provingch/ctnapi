package ctn.informatica.ctnapi.anotaciones;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Endpoint {@code /correos_tutores}: datos ficticios, DAO simulado y conexión simulada. */
class AnotacionesCorreosTutoresTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "anotaciones.api-key=clave-app-test")
    class Api {

        private static final String KEY = "clave-app-test";

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private AnotacionesDao dao;

        private void alumnoDelProfesor() throws Exception {
            when(dao.existeProfesor(2)).thenReturn(true);
            when(dao.existeAlumno(1)).thenReturn(true);
            when(dao.alumnoAsignadoAProfesor(eq(2), eq(1), anyInt())).thenReturn(true);
        }

        @Test
        void devuelveSoloCorreosValidosSinDuplicados() throws Exception {
            alumnoDelProfesor();
            when(dao.correosTutores(1)).thenReturn(Arrays.asList(
                    " madre@ejemplo.test ", "PADRE@ejemplo.test", "padre@ejemplo.test", "", "   ", null,
                    "sin-arroba", "dos@@ejemplo", "con espacio@ejemplo.test"));

            mockMvc.perform(get("/api/anotaciones/correos_tutores").header("X-API-Key", KEY)
                            .param("profesor", "2").param("alumno", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.datos.alumno_id").value(1))
                    .andExpect(jsonPath("$.datos.correos", contains("madre@ejemplo.test", "PADRE@ejemplo.test")));
        }

        @Test
        void sinTutoresDevuelveListaVacia() throws Exception {
            alumnoDelProfesor();
            when(dao.correosTutores(1)).thenReturn(List.of());
            mockMvc.perform(post("/api/anotaciones/correos_tutores").header("X-API-Key", KEY)
                            .param("profesor", "2").param("alumno", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.datos.correos", hasSize(0)));
        }

        @Test
        void alumnoDeOtroCursoDa403YNoConsultaCorreos() throws Exception {
            when(dao.existeProfesor(2)).thenReturn(true);
            when(dao.existeAlumno(1)).thenReturn(true);
            when(dao.alumnoAsignadoAProfesor(eq(2), eq(1), anyInt())).thenReturn(false);

            mockMvc.perform(get("/api/anotaciones/correos_tutores").header("X-API-Key", KEY)
                            .param("profesor", "2").param("alumno", "1"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value("El alumno no pertenece a tus cursos asignados"));
            verify(dao, never()).correosTutores(anyInt());
        }

        @Test
        void usaElAnioDeParaguayParaLaAsignacion() throws Exception {
            alumnoDelProfesor();
            when(dao.correosTutores(1)).thenReturn(List.of());
            mockMvc.perform(get("/api/anotaciones/correos_tutores").header("X-API-Key", KEY)
                    .param("profesor", "2").param("alumno", "1")).andExpect(status().isOk());
            verify(dao).alumnoAsignadoAProfesor(2, 1, LocalDate.now(AnotacionesController.PARAGUAY).getYear());
        }

        @Test
        void profesorOAlumnoInexistenteYParametrosInvalidos() throws Exception {
            when(dao.existeProfesor(2)).thenReturn(false);
            mockMvc.perform(get("/api/anotaciones/correos_tutores").header("X-API-Key", KEY)
                            .param("profesor", "2").param("alumno", "1"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("El profesor no existe"));

            when(dao.existeProfesor(2)).thenReturn(true);
            when(dao.existeAlumno(1)).thenReturn(false);
            mockMvc.perform(get("/api/anotaciones/correos_tutores").header("X-API-Key", KEY)
                            .param("profesor", "2").param("alumno", "1"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("El alumno no existe"));

            mockMvc.perform(get("/api/anotaciones/correos_tutores").header("X-API-Key", KEY).param("profesor", "2"))
                    .andExpect(status().isUnprocessableContent());
            mockMvc.perform(get("/api/anotaciones/correos_tutores").header("X-API-Key", KEY)
                            .param("profesor", "2").param("alumno", "x"))
                    .andExpect(status().isUnprocessableContent());
            verify(dao, never()).correosTutores(anyInt());
        }

        @Test
        void sinClaveDevuelve401() throws Exception {
            mockMvc.perform(get("/api/anotaciones/correos_tutores").param("profesor", "2").param("alumno", "1"))
                    .andExpect(status().isUnauthorized());
            verify(dao, never()).correosTutores(anyInt());
        }
    }

    @Nested
    class Dao {

        private final Connection con = mock(Connection.class);
        private final PreparedStatement ps = mock(PreparedStatement.class);
        private final ResultSet rs = mock(ResultSet.class);
        private final AnotacionesDao dao = new AnotacionesDao() {
            @Override
            public Connection getCon() {
                return con;
            }
        };

        private String preparar() throws Exception {
            when(con.prepareStatement(anyString())).thenReturn(ps);
            when(ps.executeQuery()).thenReturn(rs);
            return "";
        }

        private String sqlEjecutado() throws Exception {
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(con).prepareStatement(sql.capture());
            return sql.getValue();
        }

        @Test
        void correosTutoresPideSoloElCorreoDePadresVinculados() throws Exception {
            preparar();
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getString("correo")).thenReturn("madre@ejemplo.test", "tutor@ejemplo.test");

            assertEquals(List.of("madre@ejemplo.test", "tutor@ejemplo.test"), dao.correosTutores(41));

            String sql = sqlEjecutado().toLowerCase(Locale.ROOT);
            verify(ps).setInt(1, 41);
            assertTrue(sql.startsWith("select u.correo from alumno_usuario au join usuario u on u.id = au.usuario_id"));
            assertTrue(sql.contains("au.alumno_id = ?") && sql.contains("u.nivel = 4"));
            for (String campo : List.of("ci", "telefono", "celular", "nombre", "apellido", "select *", "enf_")) {
                assertFalse(sql.contains("u." + campo) || sql.contains(campo + ","), () -> "No debe pedir " + campo + ": " + sql);
            }
        }

        @Test
        void asignacionUsaParametrosYLaReglaDePromocion() throws Exception {
            preparar();
            when(rs.next()).thenReturn(true);

            assertTrue(dao.alumnoAsignadoAProfesor(2, 41, 2026));

            String sql = sqlEjecutado();
            assertTrue(sql.contains("c.promocion = ? + (3 - cb.nivel)"));
            assertTrue(sql.contains("WHERE a.usuario_id = ? AND al.id = ?"));
            verify(ps).setInt(1, 2026);
            verify(ps).setInt(2, 2);
            verify(ps).setInt(3, 41);
        }

        @Test
        void asignacionInexistenteDevuelveFalse() throws Exception {
            preparar();
            when(rs.next()).thenReturn(false);
            assertFalse(dao.alumnoAsignadoAProfesor(2, 41, 2026));
        }
    }
}
