#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${REPO_DIR:-$SCRIPT_DIR}"
SERVICE_NAME="ctnapi"
APP_USER="ctnapi"
APP_GROUP="ctnapi"
INSTALL_DIR="/opt/ctnapi"
SERVICE_UNIT="$REPO_DIR/deploy/ctnapi.service"
BACKUP_SCRIPT="$REPO_DIR/scripts/backup-db.sh"
ENV_FILE="/etc/ctnapi/ctnapi.env"
JAR_NAME="ctnapi.jar"
HEALTHCHECK_URL="${HEALTHCHECK_URL:-http://192.168.100.102:8080/health}"
STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-90}"
HEALTHCHECK_RETRIES="${HEALTHCHECK_RETRIES:-10}"
HEALTHCHECK_DELAY_SECONDS="${HEALTHCHECK_DELAY_SECONDS:-2}"

log() {
    printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"
}

fail() {
    log "ERROR: $*"
    exit 1
}

as_root() {
    if [[ "$EUID" -eq 0 ]]; then
        "$@"
    else
        sudo "$@"
    fi
}

show_service_logs() {
    as_root systemctl status "$SERVICE_NAME" --no-pager -l || true
    as_root journalctl -u "$SERVICE_NAME" -n 100 --no-pager || true
}

rollback_jar() {
    local backup_jar="$1"
    log "El health check falló; restaurando el JAR anterior."
    if [[ -s "$backup_jar" ]]; then
        local restore_tmp
        restore_tmp="$(as_root mktemp "$INSTALL_DIR/.${JAR_NAME}.restore.XXXXXX")"
        as_root cp -p "$backup_jar" "$restore_tmp"
        as_root mv -f "$restore_tmp" "$INSTALL_DIR/$JAR_NAME"
        as_root systemctl restart "$SERVICE_NAME" || true
    else
        as_root rm -f "$INSTALL_DIR/$JAR_NAME"
        as_root systemctl stop "$SERVICE_NAME" || true
    fi
    show_service_logs
}

for command in git mvn java javac curl systemctl install; do
    command -v "$command" >/dev/null 2>&1 || fail "No se encontró '$command'. Instalalo antes de desplegar."
done

if [[ "$EUID" -ne 0 ]]; then
    command -v sudo >/dev/null 2>&1 || fail "Se necesita root o sudo para instalar el servicio."
    sudo -v
fi

[[ -d "$REPO_DIR/.git" ]] || fail "No se encontró un repositorio git en $REPO_DIR."
[[ -f "$REPO_DIR/pom.xml" ]] || fail "No se encontró pom.xml en $REPO_DIR."
[[ -f "$SERVICE_UNIT" ]] || fail "No se encontró el unit de systemd: $SERVICE_UNIT."
[[ -f "$BACKUP_SCRIPT" ]] || fail "No se encontró el script de backup: $BACKUP_SCRIPT."
[[ -f "$ENV_FILE" ]] || fail "Falta $ENV_FILE. Crealo desde deploy/ctnapi.env.example y configurá sus secretos."
id "$APP_USER" >/dev/null 2>&1 || fail "No existe el usuario de servicio '$APP_USER'."
getent group "$APP_GROUP" >/dev/null 2>&1 || fail "No existe el grupo de servicio '$APP_GROUP'."

javac_version="$(javac -version 2>&1 | awk '{print $2}')"
javac_major="${javac_version%%.*}"
[[ "$javac_major" =~ ^[0-9]+$ ]] && (( javac_major >= 17 )) \
    || fail "Se requiere JDK 17 o superior para compilar; javac encontrado: ${javac_version:-desconocido}."

if [[ -n "$(git -C "$REPO_DIR" status --porcelain)" ]]; then
    fail "El repositorio tiene cambios locales. Guardalos o descartalos antes de actualizar y desplegar."
fi

log "Actualizando el repositorio (fast-forward solamente)."
git -C "$REPO_DIR" pull --ff-only

log "Ejecutando pruebas y compilando el artefacto."
mvn -f "$REPO_DIR/pom.xml" clean verify

built_jar="$REPO_DIR/target/$JAR_NAME"
[[ -s "$built_jar" ]] || fail "No se generó el artefacto esperado: $built_jar."

log "Instalando el servicio y el artefacto."
as_root install -d -o "$APP_USER" -g "$APP_GROUP" -m 755 "$INSTALL_DIR"
# De root: el usuario de servicio lo ejecuta desde el timer, pero no puede modificarlo.
as_root install -d -o root -g root -m 755 "$INSTALL_DIR/scripts"
as_root install -o root -g root -m 755 "$BACKUP_SCRIPT" "$INSTALL_DIR/scripts/backup-db.sh"
backup_jar="$(as_root mktemp "$INSTALL_DIR/.${JAR_NAME}.previous.XXXXXX")"
if as_root test -s "$INSTALL_DIR/$JAR_NAME"; then
    as_root cp -p "$INSTALL_DIR/$JAR_NAME" "$backup_jar"
fi
new_jar="$(as_root mktemp "$INSTALL_DIR/.${JAR_NAME}.new.XXXXXX")"
as_root install -o "$APP_USER" -g "$APP_GROUP" -m 644 "$built_jar" "$new_jar"
as_root mv -f "$new_jar" "$INSTALL_DIR/$JAR_NAME"
as_root install -o root -g root -m 644 "$SERVICE_UNIT" "/etc/systemd/system/$SERVICE_NAME.service"
as_root systemctl daemon-reload
as_root systemctl enable "$SERVICE_NAME"
as_root systemctl restart "$SERVICE_NAME" || {
    rollback_jar "$backup_jar"
    as_root rm -f "$backup_jar"
    fail "No se pudo reiniciar $SERVICE_NAME."
}

log "Esperando hasta ${STARTUP_TIMEOUT_SECONDS}s a que el servicio quede activo."
waited=0
while (( waited < STARTUP_TIMEOUT_SECONDS )); do
    if as_root systemctl is-active --quiet "$SERVICE_NAME"; then
        break
    fi
    sleep 1
    ((waited += 1))
done

if ! as_root systemctl is-active --quiet "$SERVICE_NAME"; then
    rollback_jar "$backup_jar"
    as_root rm -f "$backup_jar"
    fail "$SERVICE_NAME no quedó activo."
fi

attempt=1
while (( attempt <= HEALTHCHECK_RETRIES )); do
    if curl --fail --silent --max-time 5 "$HEALTHCHECK_URL" >/dev/null; then
        as_root rm -f "$backup_jar"
        log "Health check OK. Deploy terminado."
        exit 0
    fi
    sleep "$HEALTHCHECK_DELAY_SECONDS"
    ((attempt += 1))
done

rollback_jar "$backup_jar"
as_root rm -f "$backup_jar"
fail "El health check no respondió correctamente: $HEALTHCHECK_URL."
