-- Usuarios de MySQL/MariaDB para ctnapi. Se corre UNA vez como root en el servidor de base de datos.
-- Reemplazar 'IP_CONTENEDOR_CTNAPI' por la IP del contenedor de ctnapi y las claves por valores reales.
-- (Si la base corre en el mismo contenedor que ctnapi, usar 'localhost'.)

-- 1) Usuario de la aplicación: sin DDL, sin acceso al resto de las tablas de SCA.
CREATE USER IF NOT EXISTS 'ctnapi_app'@'192.168.100.102' IDENTIFIED BY 'CAMBIAR_CLAVE_APP';

-- Tablas propias (lectura y escritura de datos)
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.tipo_anotacion               TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.anotacion                    TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.anotacion_foto               TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_ficha                    TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_tutor                    TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_alergia                  TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_detalle_alergia          TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_usuario                  TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_insumo                   TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_consulta                 TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_detalle_consulta         TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_solicitud_insumo         TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_detalle_solicitud_insumo TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_reporte                  TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_consulta_personal        TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctndb.enf_detalle_consulta_personal TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';

-- Tablas de SCA: SOLO lectura, y solo las que usan estas dos APIs
GRANT SELECT ON ctndb.usuario         TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT ON ctndb.alumno          TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT ON ctndb.alumno_usuario  TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT ON ctndb.asignacion      TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT ON ctndb.curso           TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT ON ctndb.curso_base      TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT ON ctndb.especialidad    TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';
GRANT SELECT ON ctndb.materia         TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';

-- 2) Usuario de backup: solo lectura sobre toda la base (necesario si algún día se usa BACKUP_SCOPE=all).
CREATE USER IF NOT EXISTS 'ctnapi_backup'@'IP_CONTENEDOR_CTNAPI' IDENTIFIED BY 'CAMBIAR_CLAVE_BACKUP';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON ctndb.* TO 'ctnapi_backup'@'IP_CONTENEDOR_CTNAPI';

FLUSH PRIVILEGES;
