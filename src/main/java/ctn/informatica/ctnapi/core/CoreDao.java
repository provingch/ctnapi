package ctn.informatica.ctnapi.core;

import ctn.informatica.ctnapi.common.conexion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class CoreDao extends conexion {

    public List<Map<String, Object>> especialidades() throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(
                "SELECT id, nombre FROM especialidad ORDER BY nombre"); ResultSet rs = ps.executeQuery()) {
            return rows(rs);
        }
    }

    public List<Map<String, Object>> perfiles(Integer nivel) throws SQLException {
        String sql = "SELECT id, nombre, apellido, ci, telefono, celular, correo, nivel, especialidad_id "
                + "FROM usuario" + (nivel == null ? "" : " WHERE nivel = ?") + " ORDER BY apellido, nombre, id";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            if (nivel != null) ps.setInt(1, nivel);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    public Map<String, Object> perfil(int id) throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(
                "SELECT id, nombre, apellido, ci, telefono, celular, correo, nivel, especialidad_id FROM usuario WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        }
    }

    public int crearPerfil(CoreController.PerfilRequest p) throws SQLException {
        String sql = "INSERT INTO usuario (nombre, apellido, ci, telefono, celular, correo, nivel, especialidad_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setPerfil(ps, p, 1);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No se pudo obtener el ID del perfil creado");
                return keys.getInt(1);
            }
        }
    }

    public boolean actualizarPerfil(int id, CoreController.PerfilRequest p) throws SQLException {
        String sql = "UPDATE usuario SET nombre = ?, apellido = ?, ci = ?, telefono = ?, celular = ?, correo = ?, "
                + "nivel = ?, especialidad_id = ? WHERE id = ?";
        try (Connection con = getCon()) {
            int changed;
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                setPerfil(ps, p, 1);
                ps.setInt(9, id);
                changed = ps.executeUpdate();
            }
            return changed == 1 || existe(con, "SELECT 1 FROM usuario WHERE id = ?", id);
        }
    }

    private static boolean existe(Connection con, String sql, int id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void setPerfil(PreparedStatement ps, CoreController.PerfilRequest p, int start) throws SQLException {
        ps.setString(start, p.nombre());
        ps.setString(start + 1, p.apellido());
        nullableInteger(ps, start + 2, p.ci());
        ps.setString(start + 3, p.telefono());
        ps.setString(start + 4, p.celular());
        ps.setString(start + 5, p.correo());
        ps.setInt(start + 6, p.nivel());
        nullableInteger(ps, start + 7, p.especialidadId());
    }

    public boolean eliminarPerfil(int id) throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement("DELETE FROM usuario WHERE id = ?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    public List<Map<String, Object>> alumnos(Integer cursoId, Integer especialidadId) throws SQLException {
        String sql = "SELECT a.id, a.ci, a.nombre, a.apellido, a.curso_id, a.email, a.telefono FROM alumno a "
                + "JOIN curso c ON c.id = a.curso_id WHERE (? IS NULL OR a.curso_id = ?) "
                + "AND (? IS NULL OR c.especialidad_id = ?) ORDER BY a.apellido, a.nombre, a.id";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            nullableInteger(ps, 1, cursoId);
            nullableInteger(ps, 2, cursoId);
            nullableInteger(ps, 3, especialidadId);
            nullableInteger(ps, 4, especialidadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    public Map<String, Object> alumno(int id) throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(
                "SELECT id, ci, nombre, apellido, curso_id, email, telefono FROM alumno WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        }
    }

    public int crearAlumno(CoreController.AlumnoRequest a) throws SQLException {
        String sql = "INSERT INTO alumno (ci, nombre, apellido, curso_id, email, telefono) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setAlumno(ps, a, 1);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No se pudo obtener el ID del alumno creado");
                return keys.getInt(1);
            }
        }
    }

    public boolean actualizarAlumno(int id, CoreController.AlumnoRequest a, Integer actorId) throws SQLException {
        try (Connection con = getCon()) {
            boolean oldAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                Integer cursoAnterior = null;
                try (PreparedStatement ps = con.prepareStatement("SELECT curso_id FROM alumno WHERE id = ? FOR UPDATE")) {
                    ps.setInt(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            return false;
                        }
                        cursoAnterior = rs.getInt(1);
                    }
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE alumno SET ci = ?, nombre = ?, apellido = ?, curso_id = ?, email = ?, telefono = ? WHERE id = ?")) {
                    setAlumno(ps, a, 1);
                    ps.setInt(7, id);
                    ps.executeUpdate();
                }
                if (cursoAnterior != a.cursoId()) {
                    try (PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO alumno_curso_historial (alumno_id, curso_anterior_id, curso_nuevo_id, usuario_id) "
                                    + "VALUES (?, ?, ?, ?)")) {
                        ps.setInt(1, id);
                        ps.setInt(2, cursoAnterior);
                        ps.setInt(3, a.cursoId());
                        nullableInteger(ps, 4, actorId);
                        ps.executeUpdate();
                    }
                }
                con.commit();
                return true;
            } catch (SQLException ex) {
                con.rollback();
                throw ex;
            } finally {
                con.setAutoCommit(oldAutoCommit);
            }
        }
    }

    public boolean eliminarAlumno(int id) throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement("DELETE FROM alumno WHERE id = ?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    private static void setAlumno(PreparedStatement ps, CoreController.AlumnoRequest a, int start) throws SQLException {
        ps.setString(start, a.ci());
        ps.setString(start + 1, a.nombre());
        ps.setString(start + 2, a.apellido());
        ps.setInt(start + 3, a.cursoId());
        ps.setString(start + 4, a.email());
        ps.setString(start + 5, a.telefono());
    }

    public List<Map<String, Object>> cursos(Integer especialidadId, Integer promocion) throws SQLException {
        String sql = "SELECT c.id, c.especialidad_id, e.nombre AS especialidad, c.promocion, c.seccion "
                + "FROM curso c JOIN especialidad e ON e.id = c.especialidad_id "
                + "WHERE (? IS NULL OR c.especialidad_id = ?) AND (? IS NULL OR c.promocion = ?) "
                + "ORDER BY e.nombre, c.promocion, c.seccion";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            nullableInteger(ps, 1, especialidadId);
            nullableInteger(ps, 2, especialidadId);
            nullableInteger(ps, 3, promocion);
            nullableInteger(ps, 4, promocion);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    public List<Map<String, Object>> cursosBase(Integer especialidadId) throws SQLException {
        String sql = "SELECT cb.id, cb.especialidad_id, e.nombre AS especialidad, cb.nivel, cb.seccion "
                + "FROM curso_base cb JOIN especialidad e ON e.id = cb.especialidad_id "
                + "WHERE (? IS NULL OR cb.especialidad_id = ?) ORDER BY e.nombre, cb.nivel DESC, cb.seccion";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            nullableInteger(ps, 1, especialidadId);
            nullableInteger(ps, 2, especialidadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    /**
     * Cursos que dicta un profesor, según sus asignaciones, resueltos a la promoción del año {@code anio}.
     * Misma regla que SCA: el curso-base de nivel N corresponde a la promoción {@code anio + (3 - N)}
     * (nivel 3 = promoción del año actual, nivel 2 = año + 1, nivel 1 = año + 2). {@code curso_id} es el curso real
     * de esa promoción y queda en {@code null} si todavía no existe; el curso-base igual se informa.
     * No cruza con {@code materia}: una asignación con materia rota no esconde el curso.
     */
    public List<Map<String, Object>> cursosDeProfesor(int profesorId, int anio) throws SQLException {
        String sql = "SELECT DISTINCT cb.id AS curso_base_id, cb.especialidad_id, e.nombre AS especialidad, "
                + "cb.nivel, cb.seccion, ? + (3 - cb.nivel) AS promocion, c.id AS curso_id "
                + "FROM asignacion a JOIN curso_base cb ON cb.id = a.curso_base_id "
                + "JOIN especialidad e ON e.id = cb.especialidad_id "
                + "LEFT JOIN curso c ON c.especialidad_id = cb.especialidad_id AND c.seccion = cb.seccion "
                + "AND c.promocion = ? + (3 - cb.nivel) "
                + "WHERE a.usuario_id = ? ORDER BY e.nombre, cb.nivel DESC, cb.seccion";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, anio);
            ps.setInt(2, anio);
            ps.setInt(3, profesorId);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    public int asegurarCurso(CoreController.CursoRequest c) throws SQLException {
        return asegurar("INSERT INTO curso (especialidad_id, promocion, seccion) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)", c.especialidadId(), c.promocion(), c.seccion());
    }

    public int asegurarCursoBase(CoreController.CursoBaseRequest c) throws SQLException {
        return asegurar("INSERT INTO curso_base (especialidad_id, nivel, seccion) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)", c.especialidadId(), c.nivel(), c.seccion());
    }

    private int asegurar(String sql, int especialidadId, int periodo, String seccion) throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, especialidadId);
            ps.setInt(2, periodo);
            ps.setString(3, seccion);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No se pudo obtener el ID del curso");
                return keys.getInt(1);
            }
        }
    }

    public List<Map<String, Object>> materias() throws SQLException {
        String sql = "SELECT m.id, m.nombre, m.categoria, "
                + "COALESCE(GROUP_CONCAT(me.especialidad_id ORDER BY me.especialidad_id), '') AS especialidad_ids "
                + "FROM materia m LEFT JOIN materia_especialidad me ON me.materia_id = m.id "
                + "GROUP BY m.id ORDER BY m.nombre";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> result = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> item = row(rs);
                String raw = rs.getString("especialidad_ids");
                item.put("especialidad_ids", raw == null || raw.isEmpty()
                        ? List.of() : java.util.Arrays.stream(raw.split(",")).map(Integer::valueOf).toList());
                result.add(item);
            }
            return result;
        }
    }

    public int crearMateria(CoreController.MateriaRequest m) throws SQLException {
        try (Connection con = getCon()) {
            boolean oldAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                int id;
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO materia (nombre, categoria) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, m.nombre());
                    ps.setString(2, m.categoria());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("No se pudo obtener el ID de la materia creada");
                        id = keys.getInt(1);
                    }
                }
                replaceEspecialidades(con, id, m.especialidadIds());
                con.commit();
                return id;
            } catch (SQLException ex) {
                con.rollback();
                throw ex;
            } finally {
                con.setAutoCommit(oldAutoCommit);
            }
        }
    }

    public boolean actualizarMateria(int id, CoreController.MateriaRequest m) throws SQLException {
        try (Connection con = getCon()) {
            boolean oldAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement ps = con.prepareStatement("UPDATE materia SET nombre = ?, categoria = ? WHERE id = ?")) {
                    ps.setString(1, m.nombre());
                    ps.setString(2, m.categoria());
                    ps.setInt(3, id);
                    changed = ps.executeUpdate();
                }
                if (changed == 0 && !existe(con, "SELECT 1 FROM materia WHERE id = ?", id)) {
                    con.rollback();
                    return false;
                }
                replaceEspecialidades(con, id, m.especialidadIds());
                con.commit();
                return true;
            } catch (SQLException ex) {
                con.rollback();
                throw ex;
            } finally {
                con.setAutoCommit(oldAutoCommit);
            }
        }
    }

    private static void replaceEspecialidades(Connection con, int materiaId, List<Integer> ids) throws SQLException {
        try (PreparedStatement delete = con.prepareStatement("DELETE FROM materia_especialidad WHERE materia_id = ?")) {
            delete.setInt(1, materiaId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = con.prepareStatement(
                "INSERT INTO materia_especialidad (materia_id, especialidad_id) VALUES (?, ?)")) {
            for (int id : ids) {
                insert.setInt(1, materiaId);
                insert.setInt(2, id);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    public boolean eliminarMateria(int id) throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement("DELETE FROM materia WHERE id = ?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    public List<Map<String, Object>> asignaciones(Integer profesorId, Integer especialidadId) throws SQLException {
        String sql = "SELECT a.id, a.usuario_id AS profesor_id, a.materia_id, a.curso_base_id, "
                + "u.nombre AS profesor_nombre, u.apellido AS profesor_apellido, m.nombre AS materia_nombre, "
                + "e.id AS especialidad_id, e.nombre AS especialidad, cb.nivel, cb.seccion "
                + "FROM asignacion a JOIN usuario u ON u.id = a.usuario_id "
                + "JOIN materia m ON m.id = a.materia_id JOIN curso_base cb ON cb.id = a.curso_base_id "
                + "JOIN especialidad e ON e.id = cb.especialidad_id "
                + "WHERE (? IS NULL OR a.usuario_id = ?) AND (? IS NULL OR e.id = ?) "
                + "ORDER BY u.apellido, m.nombre, e.nombre, cb.nivel DESC, cb.seccion";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            nullableInteger(ps, 1, profesorId);
            nullableInteger(ps, 2, profesorId);
            nullableInteger(ps, 3, especialidadId);
            nullableInteger(ps, 4, especialidadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    public int crearAsignacion(CoreController.AsignacionRequest a) throws SQLException {
        String sql = "INSERT INTO asignacion (usuario_id, materia_id, curso_base_id) VALUES (?, ?, ?)";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, a.profesorId());
            ps.setInt(2, a.materiaId());
            ps.setInt(3, a.cursoBaseId());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No se pudo obtener el ID de la asignación");
                return keys.getInt(1);
            }
        }
    }

    public boolean actualizarAsignacion(int id, CoreController.AsignacionRequest a) throws SQLException {
        String sql = "UPDATE asignacion SET usuario_id = ?, materia_id = ?, curso_base_id = ? WHERE id = ?";
        try (Connection con = getCon()) {
            int changed;
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, a.profesorId());
                ps.setInt(2, a.materiaId());
                ps.setInt(3, a.cursoBaseId());
                ps.setInt(4, id);
                changed = ps.executeUpdate();
            }
            return changed == 1 || existe(con, "SELECT 1 FROM asignacion WHERE id = ?", id);
        }
    }

    public boolean eliminarAsignacion(int id) throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement("DELETE FROM asignacion WHERE id = ?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    public List<Map<String, Object>> vinculosFamilia(int alumnoId) throws SQLException {
        String sql = "SELECT u.id AS perfil_id, u.nombre, u.apellido, u.ci, u.telefono, u.celular, u.correo, au.parentesco "
                + "FROM alumno_usuario au JOIN usuario u ON u.id = au.usuario_id "
                + "WHERE au.alumno_id = ? ORDER BY au.parentesco, u.apellido, u.nombre";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, alumnoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    public List<Map<String, Object>> hijos(int perfilId) throws SQLException {
        String sql = "SELECT a.id, a.ci, a.nombre, a.apellido, a.curso_id, au.parentesco "
                + "FROM alumno_usuario au JOIN alumno a ON a.id = au.alumno_id "
                + "WHERE au.usuario_id = ? ORDER BY a.apellido, a.nombre";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, perfilId);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    public List<Map<String, Object>> historialCursos(int alumnoId) throws SQLException {
        String sql = "SELECT h.id, h.alumno_id, h.curso_anterior_id, h.curso_nuevo_id, h.fecha, h.usuario_id, "
                + "ca.promocion AS promocion_anterior, ca.seccion AS seccion_anterior, "
                + "ea.id AS especialidad_anterior_id, ea.nombre AS especialidad_anterior, "
                + "cn.promocion AS promocion_nueva, cn.seccion AS seccion_nueva, "
                + "en.id AS especialidad_nueva_id, en.nombre AS especialidad_nueva "
                + "FROM alumno_curso_historial h "
                + "JOIN curso ca ON ca.id = h.curso_anterior_id "
                + "JOIN especialidad ea ON ea.id = ca.especialidad_id "
                + "JOIN curso cn ON cn.id = h.curso_nuevo_id "
                + "JOIN especialidad en ON en.id = cn.especialidad_id "
                + "WHERE h.alumno_id = ? ORDER BY h.fecha DESC, h.id DESC";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, alumnoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    public boolean vincularFamilia(int alumnoId, int perfilId, String parentesco) throws SQLException {
        String sql = "INSERT INTO alumno_usuario (alumno_id, usuario_id, parentesco) VALUES (?, ?, ?)";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, alumnoId);
            ps.setInt(2, perfilId);
            ps.setString(3, parentesco);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean desvincularFamilia(int alumnoId, int perfilId) throws SQLException {
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(
                "DELETE FROM alumno_usuario WHERE alumno_id = ? AND usuario_id = ?")) {
            ps.setInt(1, alumnoId);
            ps.setInt(2, perfilId);
            return ps.executeUpdate() == 1;
        }
    }

    public List<Map<String, Object>> codigosConducta(boolean incluirInactivos) throws SQLException {
        String sql = "SELECT id, codigo, descripcion, activo FROM codigo_conducta"
                + (incluirInactivos ? "" : " WHERE activo = TRUE") + " ORDER BY codigo";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rows(rs);
        }
    }

    public int crearCodigoConducta(CoreController.CodigoConductaRequest c) throws SQLException {
        String sql = "INSERT INTO codigo_conducta (codigo, descripcion, activo) VALUES (?, ?, TRUE)";
        try (Connection con = getCon(); PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, c.codigo().trim().toUpperCase());
            ps.setString(2, c.descripcion().trim());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No se pudo obtener el ID del código de conducta");
                return keys.getInt(1);
            }
        }
    }

    public boolean actualizarCodigoConducta(int id, CoreController.CodigoConductaRequest c) throws SQLException {
        String sql = "UPDATE codigo_conducta SET codigo = ?, descripcion = ? WHERE id = ?";
        try (Connection con = getCon()) {
            int changed;
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, c.codigo().trim().toUpperCase());
                ps.setString(2, c.descripcion().trim());
                ps.setInt(3, id);
                changed = ps.executeUpdate();
            }
            return changed == 1 || existe(con, "SELECT 1 FROM codigo_conducta WHERE id = ?", id);
        }
    }

    public boolean activarCodigoConducta(int id, boolean activo) throws SQLException {
        try (Connection con = getCon()) {
            int changed;
            try (PreparedStatement ps = con.prepareStatement("UPDATE codigo_conducta SET activo = ? WHERE id = ?")) {
                ps.setBoolean(1, activo);
                ps.setInt(2, id);
                changed = ps.executeUpdate();
            }
            return changed == 1 || existe(con, "SELECT 1 FROM codigo_conducta WHERE id = ?", id);
        }
    }

    private static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        while (rs.next()) result.add(row(rs));
        return result;
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            Object value = rs.getObject(i);
            result.put(rs.getMetaData().getColumnLabel(i), value);
        }
        return result;
    }

    private static void nullableInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value);
    }
}
