package ctn.informatica.ctnapi.biblioteca;

import ctn.informatica.ctnapi.common.conexion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * Lecturas de alumnos y profesores para biblioteca. Las columnas están nombradas a propósito: teléfono,
 * correo, familia y datos de otros módulos nunca salen de este DAO. No escribe en ninguna tabla.
 */
@Repository
public class BibliotecaDao extends conexion {

    private static final String SELECT_ALUMNO = "SELECT al.id, al.ci, al.nombre, al.apellido, c.promocion, c.seccion, "
            + "e.nombre AS especialidad FROM alumno al JOIN curso c ON c.id = al.curso_id "
            + "JOIN especialidad e ON e.id = c.especialidad_id ";
    private static final String ORDEN_ALUMNO = " ORDER BY al.apellido, al.nombre, al.id";

    /** Un profesor es un perfil institucional con nivel 1. */
    private static final String SELECT_PROFESOR = "SELECT u.id, u.ci, u.nombre, u.apellido, e.nombre AS especialidad "
            + "FROM usuario u LEFT JOIN especialidad e ON e.id = u.especialidad_id ";
    private static final String ORDEN_PROFESOR = " ORDER BY u.apellido, u.nombre, u.id";

    public List<Map<String, Object>> alumnosPorCi(String ci, int anioActual) throws SQLException {
        // La CI de alumno es texto y puede tener puntos o guiones.
        return consultar(SELECT_ALUMNO + "WHERE REPLACE(REPLACE(al.ci, '.', ''), '-', '') = ?" + ORDEN_ALUMNO,
                rs -> alumno(rs, anioActual), List.of(ci));
    }

    public List<Map<String, Object>> buscarAlumnos(List<String> palabras, int pagina, int limite, int anioActual)
            throws SQLException {
        return consultar(SELECT_ALUMNO + "WHERE " + condicionPalabras("al", palabras.size()) + ORDEN_ALUMNO
                + " LIMIT ? OFFSET ?", rs -> alumno(rs, anioActual), parametrosPagina(palabras, pagina, limite));
    }

    public int contarAlumnos(List<String> palabras) throws SQLException {
        return contar("SELECT COUNT(*) FROM alumno al JOIN curso c ON c.id = al.curso_id "
                + "JOIN especialidad e ON e.id = c.especialidad_id WHERE " + condicionPalabras("al", palabras.size()),
                parametros(palabras));
    }

    public Map<String, Object> alumno(int id, int anioActual) throws SQLException {
        return primero(consultar(SELECT_ALUMNO + "WHERE al.id = ?", rs -> alumno(rs, anioActual), List.of(id)));
    }

    public List<Map<String, Object>> profesoresPorCi(String ci) throws SQLException {
        // La CI de usuario es numérica: se compara como texto para no desbordar con valores largos.
        return consultar(SELECT_PROFESOR + "WHERE u.nivel = 1 AND CAST(u.ci AS CHAR) = ?" + ORDEN_PROFESOR,
                BibliotecaDao::profesor, List.of(ci));
    }

    public List<Map<String, Object>> buscarProfesores(List<String> palabras, int pagina, int limite)
            throws SQLException {
        return consultar(SELECT_PROFESOR + "WHERE u.nivel = 1 AND " + condicionPalabras("u", palabras.size())
                + ORDEN_PROFESOR + " LIMIT ? OFFSET ?", BibliotecaDao::profesor,
                parametrosPagina(palabras, pagina, limite));
    }

    public int contarProfesores(List<String> palabras) throws SQLException {
        return contar("SELECT COUNT(*) FROM usuario u WHERE u.nivel = 1 AND "
                + condicionPalabras("u", palabras.size()), parametros(palabras));
    }

    public Map<String, Object> profesor(int id) throws SQLException {
        return primero(consultar(SELECT_PROFESOR + "WHERE u.nivel = 1 AND u.id = ?", BibliotecaDao::profesor,
                List.of(id)));
    }

    /**
     * Curso según la promoción: 3° es la promoción del año actual. Fuera de 1° a 3° (egresados o promociones
     * futuras) devuelve null, porque la base no tiene un campo que indique si el alumno sigue activo.
     */
    static String etiquetaCurso(int anioActual, int promocion, String seccion) {
        int anio = anioActual - promocion + 3;
        return anio >= 1 && anio <= 3 ? anio + "° " + seccion : null;
    }

    static String patronLike(String texto) {
        return "%" + texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String condicionPalabras(String alias, int cantidad) {
        return String.join(" AND ", Collections.nCopies(cantidad,
                "(" + alias + ".nombre LIKE ? OR " + alias + ".apellido LIKE ?)"));
    }

    private static List<Object> parametros(List<String> palabras) {
        List<Object> parametros = new ArrayList<>();
        for (String palabra : palabras) {
            String patron = patronLike(palabra);
            parametros.add(patron);
            parametros.add(patron);
        }
        return parametros;
    }

    private static List<Object> parametrosPagina(List<String> palabras, int pagina, int limite) {
        List<Object> parametros = parametros(palabras);
        parametros.add(limite);
        parametros.add((pagina - 1) * limite);
        return parametros;
    }

    private static Map<String, Object> alumno(ResultSet rs, int anioActual) throws SQLException {
        int promocion = rs.getInt("promocion");
        String seccion = rs.getString("seccion");
        String curso = etiquetaCurso(anioActual, promocion, seccion);
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("id", rs.getInt("id"));
        fila.put("ci", rs.getString("ci"));
        fila.put("nombre", rs.getString("nombre"));
        fila.put("apellido", rs.getString("apellido"));
        fila.put("curso", curso);
        fila.put("promocion", promocion);
        fila.put("seccion", seccion);
        fila.put("especialidad", rs.getString("especialidad"));
        fila.put("vigente", curso != null);
        return fila;
    }

    private static Map<String, Object> profesor(ResultSet rs) throws SQLException {
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("id", rs.getInt("id"));
        fila.put("ci", rs.getString("ci"));
        fila.put("nombre", rs.getString("nombre"));
        fila.put("apellido", rs.getString("apellido"));
        fila.put("especialidad", rs.getString("especialidad"));
        return fila;
    }

    private static Map<String, Object> primero(List<Map<String, Object>> filas) {
        return filas.isEmpty() ? null : filas.get(0);
    }

    private List<Map<String, Object>> consultar(String sql, Mapper mapper, List<?> parametros) throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            asignar(ps, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> filas = new ArrayList<>();
                while (rs.next()) filas.add(mapper.map(rs));
                return filas;
            }
        }
    }

    private int contar(String sql, List<?> parametros) throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            asignar(ps, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void asignar(PreparedStatement ps, List<?> parametros) throws SQLException {
        for (int i = 0; i < parametros.size(); i++) ps.setObject(i + 1, parametros.get(i));
    }

    @FunctionalInterface
    private interface Mapper {
        Map<String, Object> map(ResultSet rs) throws SQLException;
    }
}
