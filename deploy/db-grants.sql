-- Base y usuarios de MySQL/MariaDB exclusivos para ctnapi.
-- Ejecutar UNA vez como root en el contenedor MariaDB de ctnapi.
-- Reemplazar 'IP_CONTENEDOR_CTNAPI' en todas las cuentas por la IP/rango privado autorizado
-- del contenedor de ctnapi y reemplazar las claves por valores reales.

CREATE DATABASE IF NOT EXISTS ctnapi_db
    CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- ctnapi administra todas las tablas de ctnapi_db, pero no puede alterar el esquema.
CREATE USER IF NOT EXISTS 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI' IDENTIFIED BY 'CAMBIAR_CLAVE_APP';
GRANT SELECT, INSERT, UPDATE, DELETE ON ctnapi_db.* TO 'ctnapi_app'@'IP_CONTENEDOR_CTNAPI';

-- Usuario de backup: lectura y metadatos solo de la base de ctnapi.
CREATE USER IF NOT EXISTS 'ctnapi_backup'@'IP_CONTENEDOR_CTNAPI' IDENTIFIED BY 'CAMBIAR_CLAVE_BACKUP';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON ctnapi_db.* TO 'ctnapi_backup'@'IP_CONTENEDOR_CTNAPI';

FLUSH PRIVILEGES;
