# Traslado de anotaciones de UCTN desde SCA a ctnapi

Procedimiento **manual, verificable y reversible**. No se ejecuta solo ni forma parte de `deploy.sh`.
Copia `tipo_anotacion`, `anotacion` y `anotacion_foto` de la base de SCA a `ctnapi_db` conservando los IDs,
para que el historial y las fotos que ya cargaron los profesores sigan visibles en la app.

## Requisitos previos

1. La migración `007_anotaciones.sql` está aplicada en `ctnapi_db`. Viene del mismo `V033__anotaciones.sql`
   de SCA, pero hay que confirmar las columnas antes de copiar (paso 2).
2. La sincronización del núcleo desde SCA está completa: `alumno`, `usuario`, `materia`, `asignacion`,
   `alumno_usuario` y los cursos, **con los mismos IDs que en SCA**. Sin esto fallan las claves foráneas.
3. En `ctnapi_db` no hay anotaciones propias: `SELECT COUNT(*) FROM anotacion;` debe dar 0.
   Si hay filas, detenerse: los IDs chocarían.
4. Respaldo reciente de `ctnapi_db` (`scripts/backup-db.sh`).
5. Probar todo primero en una **copia** de `ctnapi_db`, no en la base de producción.

## 1. Exportar desde SCA (solo lectura)

```bash
mysqldump --single-transaction --no-create-info --skip-triggers --insert-ignore --hex-blob \
  -h <host_sca> -u <usuario_lectura> -p <base_sca> tipo_anotacion anotacion anotacion_foto \
  > anotaciones_sca.sql
```

Anotar los conteos de origen:

```sql
SELECT (SELECT COUNT(*) FROM tipo_anotacion) tipos, (SELECT COUNT(*) FROM anotacion) anotaciones,
       (SELECT COUNT(*) FROM anotacion_foto) fotos, (SELECT MAX(id) FROM anotacion) max_anotacion;
```

## 2. Comparar columnas

Ejecutar en ambas bases y comparar el resultado. Si difieren, no seguir: hay que adaptar el volcado.

```sql
SELECT table_name, column_name, column_type FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name IN ('tipo_anotacion','anotacion','anotacion_foto')
ORDER BY table_name, ordinal_position;
```

## 3. Importar en la copia de prueba

`tipo_anotacion` ya tiene la carga inicial de `007`/`008`. Con `--insert-ignore`, un tipo con el mismo `id`
no se pisa: revisar antes que los IDs y descripciones de ambos catálogos coincidan.

```bash
mysql -h <host_copia> -u <usuario> -p <copia_ctnapi_db> < anotaciones_sca.sql
```

## 4. Verificar

- Los conteos coinciden con los de origen.
- Sin huérfanos (todas deben dar 0):

```sql
SELECT COUNT(*) FROM anotacion a LEFT JOIN alumno al ON al.id = a.alumno_id WHERE al.id IS NULL;
SELECT COUNT(*) FROM anotacion a LEFT JOIN usuario u ON u.id = a.usuario_id WHERE u.id IS NULL;
SELECT COUNT(*) FROM anotacion_foto f LEFT JOIN anotacion a ON a.id = f.anotacion_id WHERE a.id IS NULL;
```

- `AUTO_INCREMENT` de `anotacion` y `anotacion_foto` queda por encima del máximo importado
  (`SHOW TABLE STATUS LIKE 'anotacion';`).
- Desde la app (variante `ctnapi`) el historial de un alumno de prueba muestra lo mismo que con SCA.

Recién entonces repetir los pasos 3 y 4 sobre `ctnapi_db`, en una ventana sin uso de la app y con SCA
en solo lectura para UCTN, para no perder anotaciones cargadas durante el traslado.

## Reversión

Antes de importar en `ctnapi_db` la tabla `anotacion` está vacía (requisito 3), así que revertir es quitar
solo lo importado:

```sql
DELETE FROM anotacion_foto WHERE anotacion_id <= <max_anotacion_de_origen>;
DELETE FROM anotacion WHERE id <= <max_anotacion_de_origen>;
```

Los tipos de anotación importados se dejan (no se borran registros de catálogo). Ante cualquier duda,
restaurar el respaldo del requisito 4. SCA no se modifica en ningún paso.
