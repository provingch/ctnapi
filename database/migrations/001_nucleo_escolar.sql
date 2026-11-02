-- Esquema compartido del colegio, propiedad de ctnapi.
-- Ejecutar primero en la base ctnapi_db. Los IDs se conservan al importar desde SCA.
-- usuario contiene solamente el perfil institucional; credenciales, OAuth, segundo factor,
-- sesiones y permisos de SCA permanecen en la base de SCA.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS especialidad (
    id INT NOT NULL PRIMARY KEY,
    nombre VARCHAR(45) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS curso (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    especialidad_id INT NOT NULL,
    promocion SMALLINT UNSIGNED NOT NULL,
    seccion ENUM('A', 'B', 'C') NOT NULL,
    UNIQUE KEY uq_curso_especialidad_promocion_seccion (especialidad_id, promocion, seccion),
    CONSTRAINT fk_core_curso_especialidad FOREIGN KEY (especialidad_id)
        REFERENCES especialidad (id) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS curso_base (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    especialidad_id INT NOT NULL,
    nivel TINYINT NOT NULL,
    seccion ENUM('A', 'B', 'C') NOT NULL,
    UNIQUE KEY uq_curso_base_especialidad_nivel_seccion (especialidad_id, nivel, seccion),
    CONSTRAINT fk_core_curso_base_especialidad FOREIGN KEY (especialidad_id)
        REFERENCES especialidad (id) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS materia (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(45) NOT NULL,
    categoria ENUM('comun', 'especifico') NOT NULL,
    UNIQUE KEY uq_core_materia_nombre (nombre)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS materia_especialidad (
    materia_id INT NOT NULL,
    especialidad_id INT NOT NULL,
    PRIMARY KEY (materia_id, especialidad_id),
    KEY idx_core_materia_especialidad_especialidad (especialidad_id),
    CONSTRAINT fk_core_me_materia FOREIGN KEY (materia_id)
        REFERENCES materia (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_core_me_especialidad FOREIGN KEY (especialidad_id)
        REFERENCES especialidad (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS usuario (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(45) NULL,
    apellido VARCHAR(45) NULL,
    ci INT NULL,
    telefono VARCHAR(45) NULL,
    celular VARCHAR(45) NULL,
    correo VARCHAR(255) NULL,
    nivel TINYINT NOT NULL DEFAULT 0,
    especialidad_id INT NULL,
    KEY idx_core_usuario_especialidad (especialidad_id),
    CONSTRAINT fk_core_usuario_especialidad FOREIGN KEY (especialidad_id)
        REFERENCES especialidad (id) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS alumno (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ci VARCHAR(20) NULL,
    nombre VARCHAR(45) NOT NULL,
    apellido VARCHAR(45) NOT NULL,
    curso_id INT NOT NULL,
    UNIQUE KEY uq_core_alumno_ci (ci),
    KEY idx_core_alumno_curso (curso_id),
    CONSTRAINT fk_core_alumno_curso FOREIGN KEY (curso_id)
        REFERENCES curso (id) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS alumno_usuario (
    alumno_id INT NOT NULL,
    usuario_id INT NOT NULL,
    parentesco VARCHAR(45) NULL DEFAULT 'padre',
    PRIMARY KEY (alumno_id, usuario_id),
    KEY idx_core_alumno_usuario_usuario (usuario_id),
    CONSTRAINT fk_core_au_alumno FOREIGN KEY (alumno_id)
        REFERENCES alumno (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_core_au_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuario (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS alumno_curso_historial (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alumno_id INT NOT NULL,
    curso_anterior_id INT NOT NULL,
    curso_nuevo_id INT NOT NULL,
    fecha DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    usuario_id INT NULL COMMENT 'Perfil institucional que hizo el cambio',
    KEY idx_core_ach_alumno_fecha (alumno_id, fecha),
    KEY idx_core_ach_curso_anterior (curso_anterior_id),
    KEY idx_core_ach_curso_nuevo (curso_nuevo_id),
    KEY idx_core_ach_usuario (usuario_id),
    CONSTRAINT fk_core_ach_alumno FOREIGN KEY (alumno_id)
        REFERENCES alumno (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_core_ach_curso_anterior FOREIGN KEY (curso_anterior_id)
        REFERENCES curso (id) ON UPDATE CASCADE,
    CONSTRAINT fk_core_ach_curso_nuevo FOREIGN KEY (curso_nuevo_id)
        REFERENCES curso (id) ON UPDATE CASCADE,
    CONSTRAINT fk_core_ach_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuario (id) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS asignacion (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    usuario_id INT NOT NULL,
    materia_id INT NOT NULL,
    curso_base_id INT NOT NULL,
    UNIQUE KEY uq_core_asignacion (usuario_id, materia_id, curso_base_id),
    KEY idx_core_asignacion_materia (materia_id),
    KEY idx_core_asignacion_curso_base (curso_base_id),
    CONSTRAINT fk_core_asig_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuario (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_core_asig_materia FOREIGN KEY (materia_id)
        REFERENCES materia (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_core_asig_curso_base FOREIGN KEY (curso_base_id)
        REFERENCES curso_base (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS codigo_conducta (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    codigo VARCHAR(10) NOT NULL,
    descripcion VARCHAR(255) NOT NULL,
    activo TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_core_codigo_conducta_codigo (codigo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
