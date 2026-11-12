-- Migration: contacto del alumno (email y telefono), igual que la migración 014 de SCA.
-- SCA es la fuente de verdad: las columnas se llenan con la sincronización del core snapshot.
-- Mismos tipos que en SCA (VARCHAR(150) / VARCHAR(30)), ambas NULL. Idempotente.
-- Aplicar ANTES de desplegar el JAR que las lee; si no, el import del snapshot y /alumnos fallan.

SET NAMES utf8mb4;

ALTER TABLE alumno ADD COLUMN IF NOT EXISTS email VARCHAR(150) NULL AFTER curso_id;
ALTER TABLE alumno ADD COLUMN IF NOT EXISTS telefono VARCHAR(30) NULL AFTER email;
