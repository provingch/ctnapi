#!/usr/bin/env bash
# Copia de seguridad diaria de los datos de ctnapi (la corre ctnapi-backup.timer todos los días a las 20:00).
#
# Por defecto respalda todas las tablas de ctnapi_db, propiedad exclusiva de ctnapi.
#
# Configuración: /etc/ctnapi/backup.env (variables) y /etc/ctnapi/backup.cnf (credenciales, chmod 600).
set -euo pipefail
umask 077

ENV_FILE="${CTNAPI_BACKUP_ENV:-/etc/ctnapi/backup.env}"
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && . "$ENV_FILE"

BACKUP_DIR="${BACKUP_DIR:-/var/backups/ctnapi}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
DB_NAME="${CTN_DB_NAME:-ctnapi_db}"
DB_HOST="${CTN_DB_HOST:-ctnapi-db}"
DB_PORT="${CTN_DB_PORT:-3306}"
CNF="${BACKUP_CNF:-/etc/ctnapi/backup.cnf}"

if command -v mariadb-dump >/dev/null 2>&1; then DUMP=mariadb-dump
elif command -v mysqldump >/dev/null 2>&1; then DUMP=mysqldump
else echo "ERROR: no se encontró mariadb-dump ni mysqldump" >&2; exit 1; fi
[ -r "$CNF" ] || { echo "ERROR: no se puede leer $CNF (credenciales del usuario de backup)" >&2; exit 1; }

mkdir -p "$BACKUP_DIR"
STAMP="$(TZ=America/Asuncion date +%Y%m%d-%H%M%S)"
DEST="$BACKUP_DIR/ctnapi-$STAMP.sql.gz"
TMP="$DEST.tmp"
trap 'rm -f "$TMP"' EXIT

ARGS=(--defaults-extra-file="$CNF" --host="$DB_HOST" --port="$DB_PORT"
      --single-transaction --quick --skip-lock-tables --no-tablespaces
      --default-character-set=utf8mb4 --hex-blob)
"$DUMP" "${ARGS[@]}" --routines --triggers "$DB_NAME" | gzip -9 > "$TMP"

# Verificación: el gzip tiene que estar íntegro y el volcado tiene que haber terminado ("Dump completed").
gzip -t "$TMP"
if ! gzip -dc "$TMP" | tail -n 5 | grep -q "Dump completed"; then
  echo "ERROR: el volcado quedó incompleto, no se guarda" >&2
  exit 1
fi
mv "$TMP" "$DEST"
trap - EXIT
echo "OK: $DEST ($(du -h "$DEST" | cut -f1))"

# Rotación: borra las copias de ctnapi más viejas que RETENTION_DAYS.
find "$BACKUP_DIR" -maxdepth 1 -name 'ctnapi-*.sql.gz' -type f -mtime +"$RETENTION_DAYS" -print -delete
