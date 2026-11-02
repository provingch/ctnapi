# ctnapi

API dueña de los datos escolares compartidos y de las funciones de anotaciones y enfermería. La aplicación ctnapi
y su servidor MariaDB corren en contenedores distintos. SCA y los demás proyectos consultan los datos compartidos
exclusivamente por HTTP/API; no reciben credenciales para conectarse a la base de ctnapi.

ctnapi implementa las APIs de UCTN y SHME portadas desde `ctn-sca-info`, además del Core escolar. La implementación de
enfermería conserva el contrato de rutas, parámetros y respuestas `{ok, datos}` / `{ok, error}`, pero SHME todavía
consulta la instancia de `/api/enfermeria` alojada en SCA; aún no se cambió su URL ni se migraron sus datos.

| API | Cliente | Autenticación |
|---|---|---|
| `/api/anotaciones` | UCTN (UniformeCTN), app Android de anotaciones | `X-API-Key` = `ANOTACIONES_API_KEY` |
| `/api/enfermeria` | Sistema de escritorio SHME (actualmente apunta a SCA; destino previsto: ctnapi) | `X-API-Key` = `ENFERMERIA_API_KEY` + `Authorization: Bearer` (salvo `/login`) |
| `/api/core/v1/**` | Servicios internos, inicialmente SCA | `X-Service-Key` = `CORE_API_KEY` |
| `/health` | nginx / monitor | pública, responde 200 si la base contesta, 503 si no |

Todo lo demás se rechaza (cadena de seguridad por defecto `denyAll`). La documentación de cada endpoint sigue en el
README de `ctn-sca-info` (sección "APIs para apps externas").

## Propiedad de datos y bases separadas

- `ctnapi_db` es la base propia de ctnapi. El esquema compartido inicial está en
  `database/migrations/001_nucleo_escolar.sql`; las migraciones 007, 008, 009 y 013 agregan anotaciones y enfermería.
- `usuario` en ctnapi contiene el perfil institucional (nombre, CI, contacto, nivel y especialidad), no credenciales.
  Contraseñas, identificadores/correos vinculados a Google OAuth, tokens, segundo factor, sesiones y permisos internos
  de SCA pertenecen exclusivamente a la base de SCA. El campo `correo` de ctnapi es el contacto institucional, no la
  identidad OAuth de SCA.
- SCA conservará en su propia base las planillas, evaluaciones, horarios, notificaciones y demás datos de su aplicación.
  Las referencias a IDs escolares se validan y consultan por la API, sin claves foráneas ni conexiones entre bases.
- El contenedor de ctnapi se conecta al MariaDB de ctnapi mediante `CTN_DB_HOST` por una red privada. Usar el nombre
  DNS/IP privado del contenedor MariaDB (por ejemplo `ctnapi-db:3306`), **nunca `localhost`**. No publicar el puerto
  3306 a Internet. SCA no se conecta a este MariaDB.
- Los servicios llaman a `/api/core/v1/**` por la IP privada de ctnapi, con `X-Service-Key`. Configurar
  `SERVER_ADDRESS` con la IP privada de ctnapi; permitir el puerto 8080 solo desde contenedores autorizados y bloquear
  `/api/core/` en el nginx público salvo para esas IPs. No exponer ese endpoint directamente a Internet. La clave debe
  ser aleatoria, compartida solo por ctnapi y los clientes internos autorizados, y distinta de las claves de las apps.
- Durante la transición, SCA sigue siendo la autoridad del Core. ctnapi consulta periódicamente una instantánea
  privada de solo lectura desde SCA por HTTP; no hay conexiones entre bases. Las escrituras del Core en ctnapi quedan
  bloqueadas por defecto (`CORE_WRITE_ENABLED=false`), para que la sincronización no pise cambios hechos por otra app.

La reorganización está en curso: la migración 001 crea el esquema destino y la API interna del núcleo ya está
implementada. La sincronización SCA → ctnapi del Core escolar está preparada, pero no queda activa hasta configurar
ambos servidores y validar el primer lote. UCTN y SHME siguen usando sus APIs y datos actuales en SCA; no se cambia
el tráfico de las apps existentes ni se migran sus datos custom.

### Transición de enfermería desde SCA

SHME ya está implementado y hoy usa la API de enfermería que corre dentro de SCA. La API equivalente ya está portada a
ctnapi, pero **no debe cambiarse la URL de SHME todavía**: la instancia nueva depende de las tablas `alumno`, `curso`
y `especialidad` del Core de ctnapi, además de los datos `enf_*` y los usuarios/sesiones propios de enfermería.

Por decisión actual, SHME permanece conectado a SCA y no se cambia su URL ni se migran ahora sus datos. ctnapi tiene
una implementación de enfermería preparada como posible destino futuro, pero no forma parte de la sincronización del
Core escolar. Si más adelante se decide mover SHME, será una migración separada que requiere importar sus datos y
validar la API antes de cambiar el cliente.

### Sincronización del Core escolar

La sincronización actual mueve solo el Core escolar: especialidades, cursos, cursos-base, materias, perfiles
institucionales (sin usuario/contraseña/OAuth), alumnos, vínculos familiares, historial de cursos, asignaciones y
códigos de conducta. No mueve anotaciones, enfermería ni otros datos de las apps. SCA expone
`GET /api/internal/core/snapshot` con `X-Core-Sync-Key`; ctnapi lo consulta por HTTP cada 5 minutos por defecto y
aplica la instantánea completa en una única transacción. Los IDs de SCA se conservan. Si falla una referencia o hay
un registro de ctnapi que no puede borrarse por sus FK, se revierte el lote y queda vigente la sincronización anterior.

Para habilitarlo, configurar la misma clave aleatoria de al menos 32 caracteres como `CORE_SYNC_API_KEY` en SCA y
ctnapi, establecer en SCA `CORE_SYNC_ALLOWED_IP` con la IP/CIDR privada de ctnapi y ejecutar la configuración de
nginx de SCA. En ctnapi configurar `CORE_SYNC_ENABLED=true` y
`CORE_SYNC_SOURCE_URL=http://<IP_PRIVADA_SCA>:8080/api/internal/core/snapshot`; ajustar también reglas de firewall
para que el endpoint de SCA solo sea alcanzable desde ctnapi. Mantener el sync apagado hasta que ambos despliegues,
la clave y la ruta privada estén verificados. Los errores se registran en el log del servicio y se reintentan en el
siguiente intervalo; la importación no deja un lote parcial.

### API interna del núcleo

Base URL: `http://<IP_PRIVADA_CTNAPI>:8080/api/core/v1`. Todas las rutas requieren `X-Service-Key` y devuelven
`{ "ok": true, "datos": ... }`; los errores devuelven `{ "ok": false, "error": ... }`.

| Recurso | Operaciones |
|---|---|
| `/especialidades` | `GET` |
| `/perfiles` | `GET` (filtro opcional `nivel`), `POST` |
| `/perfiles/{id}` | `GET`, `PUT`, `DELETE` |
| `/alumnos` | `GET` (filtros `cursoId`, `especialidadId`), `POST` |
| `/alumnos/{id}` | `GET`, `PUT`, `DELETE` |
| `/alumnos/{id}/historial-cursos` | `GET`; `PUT /alumnos/{id}` registra cambios de curso y actor |
| `/cursos`, `/cursos-base` | `GET`; `POST` idempotente para asegurar curso o curso-base |
| `/materias` | `GET`, `POST`; las materias incluyen `especialidadIds` |
| `/materias/{id}` | `PUT`, `DELETE` |
| `/asignaciones` | `GET` (filtros `profesorId`, `especialidadId`), `POST` |
| `/asignaciones/{id}` | `PUT`, `DELETE` |
| `/alumnos/{id}/familia`, `/perfiles/{id}/alumnos` | `GET`; crear/eliminar vínculos familiares |
| `/codigos-conducta` | `GET`, `POST`, `PUT`; activar/desactivar sin borrar el historial |

El `POST /perfiles` crea solamente el perfil institucional. Usuario/contraseña y demás credenciales se administran
en SCA, separado. `PUT /alumnos/{id}` registra en `alumno_curso_historial` los cambios de curso, incluyendo `actorId`
opcional. Las restricciones referenciales no se pueden saltar; borrados que afecten historiales devuelven conflicto.

## Estructura

```
src/main/java/ctn/informatica/ctnapi/
  common/        conexion (pool Hikari), PasswordUtil, LoginAttemptService, /health, seguridad por defecto
  enfermeria/    controller, DAO, seguridad y token propios
  anotaciones/   controller, DAO y seguridad
database/migrations/   001 núcleo compartido; 007, 008, 009 y 013 anotaciones/enfermería
deploy/                systemd, nginx, permisos de MySQL, ejemplos de variables
scripts/backup-db.sh   copia de seguridad diaria
```

## Diferencias con la versión que está dentro de SCA

- `SERVER_ADDRESS` debe ser la IP privada del contenedor para permitir llamadas internas; nginx debe poder alcanzar esa
  IP y la regla de firewall debe limitar el puerto 8080. Con `forward-headers-strategy: native`
  el bloqueo por intentos de login de enfermería usa la IP real del cliente (sin esto serían todas 127.0.0.1).
- `CTNAPI_JWT_SECRET` propio y **sin valor por defecto** (si falta, no arranca). Es distinto del `JWT_SECRET` de SCA:
  al cambiar de servidor, las sesiones de enfermería abiertas se cierran una vez y hay que volver a iniciar sesión.
- Usuario de base de datos propio (`ctnapi_app`): DML únicamente en `ctnapi_db`; sin acceso a las bases de SCA u
  otros proyectos y sin permisos DDL. Ver `deploy/db-grants.sql`.
- No corre migraciones automáticamente. Para una base descartable de desarrollo/prueba, aplicar los SQL como
  administrador, primero 001 y luego 007, 008, 009 y 013. No aplicar todavía en producción: falta definir el orden de
  importación histórica y la preservación de IDs. No conceder permisos de base de datos a SCA: sus llamadas serán a
  la API autenticada.

## Despliegue en el contenedor (Debian/Ubuntu)

```bash
apt install -y openjdk-17-jdk maven git curl nginx mariadb-client certbot python3-certbot-nginx
useradd --system --home /opt/ctnapi --shell /usr/sbin/nologin ctnapi
mkdir -p /etc/ctnapi /var/backups/ctnapi
chown ctnapi:ctnapi /var/backups/ctnapi && chmod 700 /var/backups/ctnapi

# 1) Red privada: permitir ctnapi -> MariaDB ctnapi por TCP/3306; no exponer MariaDB públicamente.
#    En CTN_DB_HOST usar el DNS/IP privado del contenedor MariaDB, no localhost.

# 2) Preparar la base de datos en el contenedor MariaDB de ctnapi.
#    Editar deploy/db-grants.sql: host autorizado y contraseñas seguras.
mysql -u root -p < deploy/db-grants.sql

# Solo en una base descartable de desarrollo/prueba, nunca en producción todavía:
# 007/008 crean y siembran catálogos; definir primero cómo importar los datos históricos y preservar sus IDs.
mariadb -h ctnapi-db -u root -p ctnapi_db < database/migrations/001_nucleo_escolar.sql
mariadb -h ctnapi-db -u root -p ctnapi_db < database/migrations/007_anotaciones.sql
mariadb -h ctnapi-db -u root -p ctnapi_db < database/migrations/008_tipos_anotacion_coordinacion.sql
mariadb -h ctnapi-db -u root -p ctnapi_db < database/migrations/009_enfermeria.sql
mariadb -h ctnapi-db -u root -p ctnapi_db < database/migrations/013_enfermeria_personal.sql

# 3) Crear la configuración de runtime una sola vez: completar IPs, claves y credenciales reales.
cp deploy/ctnapi.env.example /etc/ctnapi/ctnapi.env
chown root:ctnapi /etc/ctnapi/ctnapi.env && chmod 640 /etc/ctnapi/ctnapi.env

# 4) En el checkout del repositorio en el servidor, con cambios confirmados y rama configurada:
./deploy.sh

# 5) Configurar nginx/HTTPS una sola vez (fuera del script de deploy).
cp deploy/nginx-ctnapi.conf /etc/nginx/sites-available/ctnapi   # editar server_name
# En ese archivo, reemplazar 192.168.100.CTNAPI y 192.168.100.SCA por las IP privadas reales.
ln -s /etc/nginx/sites-available/ctnapi /etc/nginx/sites-enabled/ && nginx -t && systemctl reload nginx
certbot --nginx -d TU_DOMINIO
```

`deploy.sh` sigue el flujo de SCA: actualiza el checkout con `git pull --ff-only`, ejecuta `mvn clean verify`,
instala el JAR y el unit de systemd, reinicia el servicio y comprueba `/health`. Si el health check falla, restaura
el JAR previo. Requiere un checkout limpio, JDK 17+, Maven y sudo/root; la cuenta de servicio y
`/etc/ctnapi/ctnapi.env` deben existir antes del primer deploy. El script no instala paquetes, no modifica el `.env`,
no toca nginx y **no ejecuta migraciones ni importa datos**.

## Copias de seguridad (todos los días a las 20:00, hora de Paraguay)

`scripts/backup-db.sh` hace un `mysqldump` comprimido (`.sql.gz`) de toda `ctnapi_db` en `/var/backups/ctnapi/`,
**verifica** que el volcado haya terminado bien (si falla, no deja archivo y el
servicio queda en estado `failed`) y borra las copias de más de 14 días. Lo dispara un timer de systemd con
`OnCalendar=*-*-* 20:00:00 America/Asuncion` y `Persistent=true` (si el contenedor estaba apagado a las 20:00, corre al arrancar).

```bash
cp deploy/backup.env.example /etc/ctnapi/backup.env       # host privado de la base y retención
cp deploy/backup.cnf.example /etc/ctnapi/backup.cnf       # usuario ctnapi_backup + clave
chown ctnapi:ctnapi /etc/ctnapi/backup.cnf && chmod 600 /etc/ctnapi/backup.cnf
cp deploy/ctnapi-backup.service deploy/ctnapi-backup.timer /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now ctnapi-backup.timer

systemctl list-timers ctnapi-backup.timer        # próxima ejecución
systemctl start ctnapi-backup.service            # probar ahora
journalctl -u ctnapi-backup.service -n 20        # resultado
```

- **Restaurar**: `gunzip -c ctnapi-AAAAMMDD-HHMMSS.sql.gz | mariadb -h ctnapi-db -u root -p ctnapi_db`
- Los datos de enfermería son de salud de menores: la carpeta de backups queda `700` y los archivos `600`.
- Las copias están en el contenedor de la aplicación, separado del MariaDB; si se pierde ese contenedor, se pierden.
  Conviene sincronizar `/var/backups/ctnapi` a otro equipo o incluir ambos contenedores en el respaldo de Proxmox.

## Estado

Portado y adaptado el 2026-11-02. En curso: separar el núcleo escolar y mover los datos históricos desde la base
anterior; no se ha hecho corte de producción. El Core y la API de enfermería destino están implementados en ctnapi;
la sincronización del Core está preparada, pero requiere configurar ambas instancias y validar el primer lote. La API
de enfermería en SCA sigue siendo la instancia activa y todavía no se migraron los datos de UCTN/SHME. También queda
pendiente la API de GEMA (hoy en SCA,
`/api/integracion/**`).
