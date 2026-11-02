package ctn.informatica.ctnapi.core;

import ctn.informatica.ctnapi.common.conexion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Repository;

@Repository
public class CoreSnapshotImporter extends conexion {

    public record ImportResult(int alumnos, int perfiles, int cursos, int materias, int asignaciones) {}

    public ImportResult importar(CoreSnapshot snapshot) throws SQLException {
        validar(snapshot);
        try (Connection con = getCon()) {
            boolean oldAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                ensureUniqueIds(con, "curso", "id",
                        "especialidad_id, promocion, seccion", snapshot.cursos(),
                        row -> List.of(String.valueOf(row.especialidadId()), String.valueOf(row.promocion()), row.seccion()),
                        CoreSnapshot.Curso::id);
                ensureUniqueIds(con, "curso_base", "id",
                        "especialidad_id, nivel, seccion", snapshot.cursosBase(),
                        row -> List.of(String.valueOf(row.especialidadId()), String.valueOf(row.nivel()), row.seccion()),
                        CoreSnapshot.CursoBase::id);
                ensureUniqueIds(con, "materia", "id", "nombre", snapshot.materias(),
                        row -> List.of(row.nombre()), CoreSnapshot.Materia::id);
                ensureUniqueIds(con, "alumno", "id", "ci", snapshot.alumnos(),
                        row -> row.ci() == null ? List.of() : List.of(row.ci()), CoreSnapshot.Alumno::id);
                ensureUniqueIds(con, "asignacion", "id", "usuario_id, materia_id, curso_base_id",
                        snapshot.asignaciones(),
                        row -> List.of(String.valueOf(row.profesorId()), String.valueOf(row.materiaId()),
                                String.valueOf(row.cursoBaseId())), CoreSnapshot.Asignacion::id);
                ensureUniqueIds(con, "codigo_conducta", "id", "codigo", snapshot.codigosConducta(),
                        row -> List.of(row.codigo()), CoreSnapshot.CodigoConducta::id);

                upsert(con, "INSERT INTO especialidad (id, nombre) VALUES (?, ?) "
                                + "ON DUPLICATE KEY UPDATE nombre = VALUES(nombre)",
                        snapshot.especialidades(), (ps, row) -> {
                            ps.setInt(1, row.id());
                            ps.setString(2, row.nombre());
                        });
                upsert(con, "INSERT INTO curso (id, especialidad_id, promocion, seccion) VALUES (?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE especialidad_id = VALUES(especialidad_id), "
                                + "promocion = VALUES(promocion), seccion = VALUES(seccion)",
                        snapshot.cursos(), (ps, row) -> {
                            ps.setInt(1, row.id());
                            ps.setInt(2, row.especialidadId());
                            ps.setInt(3, row.promocion());
                            ps.setString(4, row.seccion());
                        });
                upsert(con, "INSERT INTO curso_base (id, especialidad_id, nivel, seccion) VALUES (?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE especialidad_id = VALUES(especialidad_id), "
                                + "nivel = VALUES(nivel), seccion = VALUES(seccion)",
                        snapshot.cursosBase(), (ps, row) -> {
                            ps.setInt(1, row.id());
                            ps.setInt(2, row.especialidadId());
                            ps.setInt(3, row.nivel());
                            ps.setString(4, row.seccion());
                        });
                upsert(con, "INSERT INTO materia (id, nombre, categoria) VALUES (?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE nombre = VALUES(nombre), categoria = VALUES(categoria)",
                        snapshot.materias(), (ps, row) -> {
                            ps.setInt(1, row.id());
                            ps.setString(2, row.nombre());
                            ps.setString(3, row.categoria());
                        });
                upsert(con, "INSERT INTO usuario (id, nombre, apellido, ci, telefono, celular, correo, nivel, "
                                + "especialidad_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE nombre = VALUES(nombre), apellido = VALUES(apellido), "
                                + "ci = VALUES(ci), telefono = VALUES(telefono), celular = VALUES(celular), "
                                + "correo = VALUES(correo), nivel = VALUES(nivel), "
                                + "especialidad_id = VALUES(especialidad_id)",
                        snapshot.perfiles(), (ps, row) -> {
                            ps.setInt(1, row.id());
                            ps.setString(2, row.nombre());
                            ps.setString(3, row.apellido());
                            nullableInteger(ps, 4, row.ci());
                            ps.setString(5, row.telefono());
                            ps.setString(6, row.celular());
                            ps.setString(7, row.correo());
                            ps.setInt(8, row.nivel());
                            nullableInteger(ps, 9, row.especialidadId());
                        });
                upsert(con, "INSERT INTO alumno (id, ci, nombre, apellido, curso_id) VALUES (?, ?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE ci = VALUES(ci), nombre = VALUES(nombre), "
                                + "apellido = VALUES(apellido), curso_id = VALUES(curso_id)",
                        snapshot.alumnos(), (ps, row) -> {
                            ps.setInt(1, row.id());
                            ps.setString(2, row.ci());
                            ps.setString(3, row.nombre());
                            ps.setString(4, row.apellido());
                            ps.setInt(5, row.cursoId());
                        });
                upsert(con, "INSERT INTO materia_especialidad (materia_id, especialidad_id) VALUES (?, ?) "
                                + "ON DUPLICATE KEY UPDATE especialidad_id = VALUES(especialidad_id)",
                        snapshot.materiasEspecialidades(), (ps, row) -> {
                            ps.setInt(1, row.materiaId());
                            ps.setInt(2, row.especialidadId());
                        });
                upsert(con, "INSERT INTO alumno_usuario (alumno_id, usuario_id, parentesco) VALUES (?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE parentesco = VALUES(parentesco)",
                        snapshot.vinculosFamiliares(), (ps, row) -> {
                            ps.setInt(1, row.alumnoId());
                            ps.setInt(2, row.perfilId());
                            ps.setString(3, row.parentesco());
                        });
                upsert(con, "INSERT INTO alumno_curso_historial "
                                + "(id, alumno_id, curso_anterior_id, curso_nuevo_id, fecha, usuario_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                                + "alumno_id = VALUES(alumno_id), curso_anterior_id = VALUES(curso_anterior_id), "
                                + "curso_nuevo_id = VALUES(curso_nuevo_id), fecha = VALUES(fecha), "
                                + "usuario_id = VALUES(usuario_id)",
                        snapshot.historialCursos(), (ps, row) -> {
                            ps.setInt(1, row.id());
                            ps.setInt(2, row.alumnoId());
                            ps.setInt(3, row.cursoAnteriorId());
                            ps.setInt(4, row.cursoNuevoId());
                            ps.setTimestamp(5, Timestamp.valueOf(row.fecha()));
                            nullableInteger(ps, 6, row.perfilId());
                        });
                upsert(con, "INSERT INTO asignacion (id, usuario_id, materia_id, curso_base_id) VALUES (?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE usuario_id = VALUES(usuario_id), "
                                + "materia_id = VALUES(materia_id), curso_base_id = VALUES(curso_base_id)",
                        snapshot.asignaciones(), (ps, row) -> {
                            ps.setInt(1, row.id());
                            ps.setInt(2, row.profesorId());
                            ps.setInt(3, row.materiaId());
                            ps.setInt(4, row.cursoBaseId());
                        });
                upsert(con, "INSERT INTO codigo_conducta (id, codigo, descripcion, activo, created_at) "
                                + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE codigo = VALUES(codigo), "
                                + "descripcion = VALUES(descripcion), activo = VALUES(activo), "
                                + "created_at = VALUES(created_at)",
                        snapshot.codigosConducta(), (ps, row) -> {
                            ps.setInt(1, row.id());
                            ps.setString(2, row.codigo());
                            ps.setString(3, row.descripcion());
                            ps.setBoolean(4, row.activo());
                            ps.setTimestamp(5, Timestamp.valueOf(row.createdAt()));
                        });

                deleteMissing(con, "alumno_curso_historial", "id",
                        snapshot.historialCursos().stream().map(CoreSnapshot.HistorialCurso::id).toList());
                deleteMissing(con, "asignacion", "id",
                        snapshot.asignaciones().stream().map(CoreSnapshot.Asignacion::id).toList());
                deleteMissing(con, "alumno_usuario", "alumno_id, usuario_id",
                        snapshot.vinculosFamiliares().stream()
                                .map(row -> row.alumnoId() + ":" + row.perfilId()).toList());
                deleteMissing(con, "materia_especialidad", "materia_id, especialidad_id",
                        snapshot.materiasEspecialidades().stream()
                                .map(row -> row.materiaId() + ":" + row.especialidadId()).toList());
                deleteMissing(con, "alumno", "id", snapshot.alumnos().stream().map(CoreSnapshot.Alumno::id).toList());
                deleteMissing(con, "usuario", "id", snapshot.perfiles().stream().map(CoreSnapshot.Perfil::id).toList());
                deleteMissing(con, "materia", "id", snapshot.materias().stream().map(CoreSnapshot.Materia::id).toList());
                deleteMissing(con, "curso_base", "id",
                        snapshot.cursosBase().stream().map(CoreSnapshot.CursoBase::id).toList());
                deleteMissing(con, "curso", "id", snapshot.cursos().stream().map(CoreSnapshot.Curso::id).toList());
                deleteMissing(con, "codigo_conducta", "id",
                        snapshot.codigosConducta().stream().map(CoreSnapshot.CodigoConducta::id).toList());
                deleteMissing(con, "especialidad", "id",
                        snapshot.especialidades().stream().map(CoreSnapshot.Especialidad::id).toList());
                con.commit();
                return new ImportResult(snapshot.alumnos().size(), snapshot.perfiles().size(),
                        snapshot.cursos().size(), snapshot.materias().size(), snapshot.asignaciones().size());
            } catch (SQLException | RuntimeException ex) {
                con.rollback();
                throw ex;
            } finally {
                con.setAutoCommit(oldAutoCommit);
            }
        }
    }

    private static void validar(CoreSnapshot snapshot) {
        if (snapshot == null || snapshot.version() != 1 || snapshot.generatedAt() == null) {
            throw new IllegalArgumentException("La versión o fecha del snapshot del Core no es válida");
        }
        requireRows(snapshot.especialidades(), "especialidades");
        requireRows(snapshot.cursos(), "cursos");
        requireRows(snapshot.cursosBase(), "cursosBase");
        requireRows(snapshot.materias(), "materias");
        requireRows(snapshot.perfiles(), "perfiles");
        requireRows(snapshot.alumnos(), "alumnos");
        requireRows(snapshot.codigosConducta(), "codigosConducta");
        requireList(snapshot.materiasEspecialidades(), "materiasEspecialidades");
        requireList(snapshot.vinculosFamiliares(), "vinculosFamiliares");
        requireList(snapshot.historialCursos(), "historialCursos");
        requireList(snapshot.asignaciones(), "asignaciones");
    }

    private static void requireRows(List<?> rows, String name) {
        requireList(rows, name);
        if (rows.isEmpty()) throw new IllegalArgumentException("El snapshot no puede dejar vacío el catálogo " + name);
    }

    private static void requireList(List<?> rows, String name) {
        if (rows == null || rows.size() > 100_000) {
            throw new IllegalArgumentException("La lista " + name + " está ausente o supera el máximo permitido");
        }
    }

    private static <T> void upsert(Connection con, String sql, List<T> rows, Binder<T> binder) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int pending = 0;
            for (T row : rows) {
                binder.bind(ps, row);
                ps.addBatch();
                if (++pending == 500) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) ps.executeBatch();
        }
    }

    private static void deleteMissing(Connection con, String table, String keyColumns, List<?> keys)
            throws SQLException {
        String[] columns = keyColumns.split(", ");
        String condition;
        if (keys.isEmpty()) {
            condition = "1 = 1";
        } else if (columns.length == 1) {
            condition = columns[0] + " NOT IN (" + String.join(",", java.util.Collections.nCopies(keys.size(), "?")) + ")";
        } else {
            String expression = "CONCAT(" + String.join(", ':', ", columns) + ")";
            condition = expression + " NOT IN (" + String.join(",", java.util.Collections.nCopies(keys.size(), "?")) + ")";
        }
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + table + " WHERE " + condition)) {
            for (int i = 0; i < keys.size(); i++) ps.setObject(i + 1, keys.get(i));
            ps.executeUpdate();
        }
    }

    private static <T> void ensureUniqueIds(
            Connection con, String table, String idColumn, String keyColumns, List<T> incoming,
            Function<T, List<String>> keyForRow, Function<T, Integer> idForRow) throws SQLException {
        Map<List<String>, Integer> existing = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT " + idColumn + ", " + keyColumns + " FROM " + table);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                List<String> key = new ArrayList<>();
                boolean nullableKey = false;
                for (int i = 2; i <= rs.getMetaData().getColumnCount(); i++) {
                    String value = rs.getString(i);
                    if (value == null) nullableKey = true;
                    key.add(value == null ? null : canonical(value));
                }
                if (!nullableKey) existing.put(key, rs.getInt(1));
            }
        }
        for (T row : incoming) {
            List<String> key = keyForRow.apply(row).stream().map(CoreSnapshotImporter::canonical).toList();
            if (key.isEmpty()) continue;
            Integer existingId = existing.get(key);
            int incomingId = idForRow.apply(row);
            if (existingId != null && existingId != incomingId) {
                throw new SQLException("La clave única de " + table
                        + " ya pertenece a un ID distinto; se cancela la sincronización para preservar IDs");
            }
        }
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static void nullableInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value);
    }

    @FunctionalInterface
    private interface Binder<T> {
        void bind(PreparedStatement ps, T row) throws SQLException;
    }
}
