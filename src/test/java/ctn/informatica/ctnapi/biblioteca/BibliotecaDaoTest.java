package ctn.informatica.ctnapi.biblioteca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Comprueba SQL, parámetros y mapeo con una conexión simulada. No valida el SQL contra MariaDB. */
class BibliotecaDaoTest {

    private final Connection con = mock(Connection.class);
    private final PreparedStatement ps = mock(PreparedStatement.class);
    private final ResultSet rs = mock(ResultSet.class);
    private final BibliotecaDao dao = new BibliotecaDao() {
        @Override
        public Connection getCon() {
            return con;
        }
    };

    @BeforeEach
    void preparar() throws SQLException {
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
    }

    private String sqlEjecutado() throws SQLException {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(con).prepareStatement(sql.capture());
        return sql.getValue();
    }

    private static void noPideDatosAjenos(String sql) {
        String texto = sql.toLowerCase(Locale.ROOT);
        for (String campo : List.of("telefono", "celular", "correo", "alumno_usuario", "enf_", "contras", "select *")) {
            assertFalse(texto.contains(campo), () -> "La consulta no debe incluir " + campo + ": " + sql);
        }
    }

    @Test
    void alumnoPorCiComparaSinPuntosNiGuionesYMapeaCurso() throws Exception {
        when(rs.next()).thenReturn(true, false);
        when(rs.getInt("id")).thenReturn(1842);
        when(rs.getString("ci")).thenReturn("5.123.456");
        when(rs.getString("nombre")).thenReturn("María");
        when(rs.getString("apellido")).thenReturn("González");
        when(rs.getInt("promocion")).thenReturn(2027);
        when(rs.getString("seccion")).thenReturn("A");
        when(rs.getString("especialidad")).thenReturn("Informática");

        List<Map<String, Object>> filas = dao.alumnosPorCi("5123456", 2026);

        String sql = sqlEjecutado();
        assertTrue(sql.contains("REPLACE(REPLACE(al.ci, '.', ''), '-', '') = ?"));
        noPideDatosAjenos(sql);
        verify(ps).setObject(1, "5123456");
        assertEquals(List.of("id", "ci", "nombre", "apellido", "curso", "promocion", "seccion", "especialidad", "vigente"),
                List.copyOf(filas.get(0).keySet()));
        assertEquals("2° A", filas.get(0).get("curso"));
        assertEquals(true, filas.get(0).get("vigente"));
    }

    @Test
    void egresadoQuedaSinCursoYNoVigente() throws Exception {
        when(rs.next()).thenReturn(true, false);
        when(rs.getInt("promocion")).thenReturn(2024);
        when(rs.getString("seccion")).thenReturn("B");

        Map<String, Object> fila = dao.alumno(1, 2026);

        assertNull(fila.get("curso"));
        assertEquals(false, fila.get("vigente"));
    }

    @Test
    void buscarAlumnosExigeCadaPalabraYPagina() throws Exception {
        when(rs.next()).thenReturn(false);

        assertTrue(dao.buscarAlumnos(List.of("juan", "per%z"), 3, 20, 2026).isEmpty());

        String sql = sqlEjecutado();
        assertTrue(sql.contains("(al.nombre LIKE ? OR al.apellido LIKE ?) AND (al.nombre LIKE ? OR al.apellido LIKE ?)"));
        assertTrue(sql.endsWith("LIMIT ? OFFSET ?"));
        noPideDatosAjenos(sql);
        verify(ps).setObject(1, "%juan%");
        verify(ps).setObject(2, "%juan%");
        verify(ps).setObject(3, "%per\\%z%");
        verify(ps).setObject(4, "%per\\%z%");
        verify(ps).setObject(5, 20);
        verify(ps).setObject(6, 40);
    }

    @Test
    void contarAlumnosUsaLasMismasPalabras() throws Exception {
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(7);

        assertEquals(7, dao.contarAlumnos(List.of("ana")));

        verify(ps).setObject(1, "%ana%");
        verify(ps).setObject(2, "%ana%");
    }

    @Test
    void alumnoInexistenteDevuelveNull() throws Exception {
        when(rs.next()).thenReturn(false);

        assertNull(dao.alumno(99, 2026));
        verify(ps).setObject(1, 99);
    }

    @Test
    void profesoresSiempreFiltranPorNivelUno() throws Exception {
        when(rs.next()).thenReturn(false);

        dao.profesoresPorCi("3456789");

        String sql = sqlEjecutado();
        assertTrue(sql.contains("WHERE u.nivel = 1 AND CAST(u.ci AS CHAR) = ?"));
        noPideDatosAjenos(sql);
        verify(ps).setObject(1, "3456789");
    }

    @Test
    void buscarProfesoresFiltraNivelYPagina() throws Exception {
        when(rs.next()).thenReturn(false);

        dao.buscarProfesores(List.of("ben"), 2, 10);

        String sql = sqlEjecutado();
        assertTrue(sql.contains("WHERE u.nivel = 1 AND (u.nombre LIKE ? OR u.apellido LIKE ?)"));
        noPideDatosAjenos(sql);
        verify(ps).setObject(3, 10);
        verify(ps).setObject(4, 10);
    }

    @Test
    void contarYConsultarProfesorFiltranNivel() throws Exception {
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getInt(1)).thenReturn(1);
        when(rs.getInt("id")).thenReturn(317);

        dao.contarProfesores(List.of("ben"));
        Map<String, Object> profesor = dao.profesor(317);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(con, org.mockito.Mockito.times(2)).prepareStatement(sql.capture());
        sql.getAllValues().forEach(s -> assertTrue(s.contains("u.nivel = 1"), s));
        assertEquals(List.of("id", "ci", "nombre", "apellido", "especialidad"), List.copyOf(profesor.keySet()));
    }

    @Test
    void etiquetaCursoSoloDePrimeroATercero() {
        assertEquals("3° A", BibliotecaDao.etiquetaCurso(2026, 2026, "A"));
        assertEquals("1° C", BibliotecaDao.etiquetaCurso(2026, 2028, "C"));
        assertNull(BibliotecaDao.etiquetaCurso(2026, 2025, "A"));
        assertNull(BibliotecaDao.etiquetaCurso(2026, 2029, "A"));
    }

    @Test
    void escapaComodinesDeLike() {
        assertEquals("%a\\%b\\_c\\\\d%", BibliotecaDao.patronLike("a%b_c\\d"));
    }
}
