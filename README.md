# ctnapi

API para todos los servicios externos que requieran la base de datos del CTN. Corre en su propio contenedor,
separada de SCA, para no sobrecargarlo.

Hoy incluye (portado desde `ctn-sca-info`, `backend/.../integration/`, con el mismo contrato de URLs, parámetros y
respuestas `{ok, datos}` / `{ok, error}`):

| API | Cliente | Autenticación |
|---|---|---|
| `/api/anotaciones` | App Android de anotaciones | `X-API-Key` = `ANOTACIONES_API_KEY` |
| `/api/enfermeria` | Sistema de escritorio SHME | `X-API-Key` = `ENFERMERIA_API_KEY` + `Authorization: Bearer` (salvo `/login`) |
| `/health` | nginx / monitor | pública, responde 200 si la base contesta, 503 si no |

Todo lo demás se rechaza (cadena de seguridad por defecto `denyAll`). La documentación de cada endpoint sigue en el
README de `ctn-sca-info` (sección "APIs para apps externas").

## Estructura

```
src/main/java/ctn/informatica/ctnapi/
  common/        conexion (pool Hikari), PasswordUtil, LoginAttemptService, /health, seguridad por defecto
  enfermeria/    controller, DAO, seguridad y token propios
  anotaciones/   controller, DAO y seguridad
database/migrations/   007, 008, 009 y 013 de SCA (solo referencia: las tablas ya existen en ctndb)
deploy/                systemd, nginx, permisos de MySQL, ejemplos de variables
scripts/backup-db.sh   copia de seguridad diaria
```

## Diferencias con la versión que está dentro de SCA

- Escucha solo en `127.0.0.1`; lo alcanza únicamente el nginx del mismo contenedor. Con `forward-headers-strategy: native`
  el bloqueo por intentos de login de enfermería usa la IP real del cliente (sin esto serían todas 127.0.0.1).
- `CTNAPI_JWT_SECRET` propio y **sin valor por defecto** (si falta, no arranca). Es distinto del `JWT_SECRET` de SCA:
  al cambiar de servidor, las sesiones de enfermería abiertas se cierran una vez y hay que volver a iniciar sesión.
- Usuario de base de datos propio (`ctnapi_app`): solo DML sobre sus tablas y `SELECT` sobre `usuario`, `alumno`,
  `alumno_usuario`, `asignacion`, `curso`, `curso_base`, `especialidad`, `materia`. Ver `deploy/db-grants.sql`.
- No corre migraciones: no necesita permisos DDL. Si hay que cambiar el esquema, se aplica a mano con un usuario administrador.

## Despliegue en el contenedor (Debian/Ubuntu)

```bash
apt install -y openjdk-17-jre-headless nginx mariadb-client   # o default-mysql-client
useradd --system --home /opt/ctnapi --shell /usr/sbin/nologin ctnapi
mkdir -p /opt/ctnapi/scripts /etc/ctnapi /var/backups/ctnapi
chown ctnapi:ctnapi /var/backups/ctnapi && chmod 700 /var/backups/ctnapi

# 1) Base de datos (en el servidor de MySQL, una vez): editar IP y claves y correr
mysql -u root -p < deploy/db-grants.sql

# 2) Compilar (en tu PC o en el contenedor) y copiar el jar
./mvnw -DskipTests package            # o: mvn package    -> target/ctnapi.jar
cp target/ctnapi.jar /opt/ctnapi/ && cp scripts/backup-db.sh /opt/ctnapi/scripts/

# 3) Configuración
cp deploy/ctnapi.env.example /etc/ctnapi/ctnapi.env      # completar claves
chown root:ctnapi /etc/ctnapi/ctnapi.env && chmod 640 /etc/ctnapi/ctnapi.env

# 4) Servicio
cp deploy/ctnapi.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now ctnapi
curl -s http://127.0.0.1:8080/health

# 5) nginx + HTTPS
cp deploy/nginx-ctnapi.conf /etc/nginx/sites-available/ctnapi   # editar server_name
ln -s /etc/nginx/sites-available/ctnapi /etc/nginx/sites-enabled/ && nginx -t && systemctl reload nginx
certbot --nginx -d TU_DOMINIO
```

> El repo no trae `mvnw` todavía: copiá `mvnw`, `mvnw.cmd` y `.mvn/` de `ctn-sca-info/backend/` o usá `mvn`.

## Copias de seguridad (todos los días a las 20:00, hora de Paraguay)

`scripts/backup-db.sh` hace un `mysqldump` comprimido (`.sql.gz`) de las tablas que administra ctnapi (anotaciones y
enfermería) en `/var/backups/ctnapi/`, **verifica** que el volcado haya terminado bien (si falla, no deja archivo y el
servicio queda en estado `failed`) y borra las copias de más de 14 días. Lo dispara un timer de systemd con
`OnCalendar=*-*-* 20:00:00 America/Asuncion` y `Persistent=true` (si el contenedor estaba apagado a las 20:00, corre al arrancar).

```bash
cp deploy/backup.env.example /etc/ctnapi/backup.env       # host de la base, retención, alcance
cp deploy/backup.cnf.example /etc/ctnapi/backup.cnf       # usuario ctnapi_backup + clave
chown ctnapi:ctnapi /etc/ctnapi/backup.cnf && chmod 600 /etc/ctnapi/backup.cnf
cp deploy/ctnapi-backup.service deploy/ctnapi-backup.timer /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now ctnapi-backup.timer

systemctl list-timers ctnapi-backup.timer        # próxima ejecución
systemctl start ctnapi-backup.service            # probar ahora
journalctl -u ctnapi-backup.service -n 20        # resultado
```

- `BACKUP_SCOPE=own` (por defecto): solo las 16 tablas de ctnapi. `BACKUP_SCOPE=all`: toda `ctndb` (las tablas de SCA
  ya las respalda SCA; úsalo solo si querés una copia extra).
- **Restaurar** (las tablas del volcado se recrean y reemplazan): `gunzip -c ctnapi-own-AAAAMMDD-HHMMSS.sql.gz | mysql -u root -p ctndb`
- Los datos de enfermería son de salud de menores: la carpeta de backups queda `700` y los archivos `600`.
- Las copias están en el **mismo** contenedor: si se pierde el contenedor, se pierden con él. Conviene sumar el contenedor
  al respaldo de Proxmox (vzdump) o sincronizar `/var/backups/ctnapi` a otro equipo.

## Estado

Portado y adaptado el 2026-11-02. **Todavía no se compiló ni se corrió contra la base real** (el entorno donde se armó no
tiene Maven). Primer paso al clonarlo: `./mvnw test` y luego probar `/health` y `/api/enfermeria/login`.
Pendiente: API de GEMA (hoy en SCA, `/api/integracion/**`).
