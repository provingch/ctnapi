#!/usr/bin/env bash
# Deploy y mantenimiento de ctnapi.  ./deploy.sh --help
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${REPO_DIR:-$SCRIPT_DIR}"
SERVICE_NAME="ctnapi"
APP_USER="ctnapi"
APP_GROUP="ctnapi"
INSTALL_DIR="/opt/ctnapi"
SERVICE_UNIT="$REPO_DIR/deploy/ctnapi.service"
BACKUP_SCRIPT="$REPO_DIR/scripts/backup-db.sh"
BACKUP_UNIT="$REPO_DIR/deploy/ctnapi-backup.service"
BACKUP_TIMER="$REPO_DIR/deploy/ctnapi-backup.timer"
BACKUP_CNF="/etc/ctnapi/backup.cnf"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/ctnapi}"
MIGRATIONS_DIR="$REPO_DIR/database/migrations"
MIGRATIONS_TABLE="ctnapi_migracion"
ENV_FILE="/etc/ctnapi/ctnapi.env"
JAR_NAME="ctnapi.jar"
HEALTHCHECK_URL="${HEALTHCHECK_URL:-http://192.168.100.102:8080/health}"
STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-90}"
HEALTHCHECK_RETRIES="${HEALTHCHECK_RETRIES:-10}"
HEALTHCHECK_DELAY_SECONDS="${HEALTHCHECK_DELAY_SECONDS:-2}"
# Las migraciones se aplican como root (el usuario de la app no tiene DDL). Por defecto: socket local.
# Para otra base: DB_ADMIN_ARGS="-h ctnapi-db -u root -p" ./deploy.sh ...
DB_ADMIN_ARGS="${DB_ADMIN_ARGS:-}"
# Sin terminal (cron/CI) las migraciones pendientes frenan el deploy, salvo APLICAR_MIGRACIONES=1.
APLICAR_MIGRACIONES="${APLICAR_MIGRACIONES:-0}"

# ---------- salida ----------
if [[ -t 1 ]]; then
    C_RESET=$'\e[0m' C_BOLD=$'\e[1m' C_GRAY=$'\e[90m' C_RED=$'\e[31m' C_GREEN=$'\e[32m' C_YELLOW=$'\e[33m' C_CYAN=$'\e[36m'
else
    C_RESET='' C_BOLD='' C_GRAY='' C_RED='' C_GREEN='' C_YELLOW='' C_CYAN=''
fi
if [[ "$EUID" -eq 0 ]]; then DEPLOY_LOG="${DEPLOY_LOG:-/var/log/ctnapi/deploy.log}"; else DEPLOY_LOG="${DEPLOY_LOG:-$HOME/.ctnapi-deploy.log}"; fi
mkdir -p "$(dirname "$DEPLOY_LOG")" 2>/dev/null || true

_archivo() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$DEPLOY_LOG" 2>/dev/null || true; }
log()      { printf '%s\n' "  ${C_GRAY}[$(date '+%H:%M:%S')]${C_RESET} $*"; _archivo "$*"; }
log_ok()   { printf '%s\n' "  ${C_GREEN}✔${C_RESET} $*"; _archivo "OK $*"; }
log_warn() { printf '%s\n' "  ${C_YELLOW}▲${C_RESET} $*"; _archivo "WARN $*"; }
log_err()  { printf '%s\n' "  ${C_RED}✘${C_RESET} $*" >&2; _archivo "ERROR $*"; }
section()  { printf '\n%s\n' "${C_BOLD}${C_CYAN}── $* ──${C_RESET}"; _archivo "== $*"; }
fail()     { log_err "$*"; exit 1; }

interactivo() { [[ -t 0 ]]; }
confirmar() {
    interactivo || return 1
    local r
    read -r -p "  $1 [s/N]: " r
    [[ "$r" =~ ^[sSyY]$ ]]
}

as_root() {
    if [[ "$EUID" -eq 0 ]]; then "$@"; else sudo "$@"; fi
}

asegurar_root() {
    if [[ "$EUID" -ne 0 ]]; then
        command -v sudo >/dev/null 2>&1 || fail "Se necesita root o sudo."
        sudo -v
    fi
}

show_service_logs() {
    as_root systemctl status "$SERVICE_NAME" --no-pager -l || true
    as_root journalctl -u "$SERVICE_NAME" -n 100 --no-pager || true
}

# ---------- base de datos ----------
db_name() {
    local v
    v="$(as_root sed -n 's/^[[:space:]]*CTN_DB_NAME=//p' "$ENV_FILE" 2>/dev/null | tail -n 1 | tr -d "\"'[:space:]")"
    printf '%s' "${v:-ctnapi_db}"
}

db_client() {
    if command -v mariadb >/dev/null 2>&1; then printf mariadb
    elif command -v mysql >/dev/null 2>&1; then printf mysql
    else fail "No se encontró el cliente mariadb/mysql."; fi
}

# sql "consulta"  → filas separadas por tab, sin encabezado.  sql < archivo.sql → ejecuta el archivo.
sql() {
    # shellcheck disable=SC2086  # DB_ADMIN_ARGS se separa a propósito
    if (( $# )); then
        as_root "$(db_client)" --default-character-set=utf8mb4 $DB_ADMIN_ARGS -N -B "$(db_name)" -e "$1"
    else
        as_root "$(db_client)" --default-character-set=utf8mb4 $DB_ADMIN_ARGS "$(db_name)"
    fi
}

tabla_migraciones_existe() {
    [[ "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '$MIGRATIONS_TABLE'")" == "1" ]]
}

crear_tabla_migraciones() {
    sql "CREATE TABLE IF NOT EXISTS $MIGRATIONS_TABLE (
            archivo VARCHAR(255) NOT NULL PRIMARY KEY,
            sha256 CHAR(64) NOT NULL,
            origen VARCHAR(20) NOT NULL COMMENT 'aplicada = la corrió deploy.sh; registrada = ya estaba aplicada a mano',
            aplicada_en TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
         ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Migraciones de database/migrations aplicadas (lo mantiene deploy.sh)'"
}

registrar_migracion() {  # archivo sha origen
    sql "INSERT INTO $MIGRATIONS_TABLE (archivo, sha256, origen) VALUES ('$1', '$2', '$3')
         ON DUPLICATE KEY UPDATE sha256 = VALUES(sha256)"
}

archivos_migracion() {
    find "$MIGRATIONS_DIR" -maxdepth 1 -type f -name '[0-9]*.sql' -printf '%f\n' | sort -V
}

sha_de() { sha256sum "$MIGRATIONS_DIR/$1" | cut -d' ' -f1; }

PENDIENTES=()
# Llena PENDIENTES y muestra el estado. Devuelve 2 si todavía no hay registro de migraciones.
revisar_migraciones() {
    PENDIENTES=()
    [[ -d "$MIGRATIONS_DIR" ]] || fail "No existe $MIGRATIONS_DIR."
    if ! tabla_migraciones_existe; then
        log_warn "La base $(db_name) todavía no tiene registro de migraciones ($MIGRATIONS_TABLE)."
        log_warn "Verificá cuáles están aplicadas y registralas con: ./deploy.sh --registrar-migraciones [hasta]"
        return 2
    fi
    local -A registradas=()
    local archivo sha
    while IFS=$'\t' read -r archivo sha; do
        [[ -n "$archivo" ]] && registradas["$archivo"]="$sha"
    done < <(sql "SELECT archivo, sha256 FROM $MIGRATIONS_TABLE")
    while read -r archivo; do
        sha="$(sha_de "$archivo")"
        if [[ -z "${registradas[$archivo]:-}" ]]; then
            PENDIENTES+=("$archivo")
            printf '    %-45s %s\n' "$archivo" "${C_YELLOW}PENDIENTE${C_RESET}"
        elif [[ "${registradas[$archivo]}" != "$sha" ]]; then
            printf '    %-45s %s\n' "$archivo" "${C_RED}MODIFICADA después de aplicarse (revisar a mano)${C_RESET}"
        else
            printf '    %-45s %s\n' "$archivo" "${C_GREEN}aplicada${C_RESET}"
        fi
    done < <(archivos_migracion)
    if (( ${#PENDIENTES[@]} )); then log_warn "${#PENDIENTES[@]} migración(es) pendiente(s)."; else log_ok "Migraciones al día."; fi
}

# ---------- backup ----------
backup_ahora() {
    section "Backup de $(db_name)"
    if ! as_root test -f "/etc/systemd/system/ctnapi-backup.service"; then
        log_err "El servicio de backup no está instalado (se instala con --update o --instalar-backup)."
        return 1
    fi
    if ! as_root systemctl start ctnapi-backup.service; then
        log_err "Falló el backup."
        as_root journalctl -u ctnapi-backup.service -n 20 --no-pager || true
        return 1
    fi
    local ultimo
    ultimo="$(as_root find "$BACKUP_DIR" -maxdepth 1 -name 'ctnapi-*.sql.gz' -mmin -5 -printf '%T@ %p\n' 2>/dev/null | sort -n | tail -n 1 | cut -d' ' -f2-)"
    [[ -n "$ultimo" ]] || { log_err "El backup terminó pero no aparece un archivo nuevo en $BACKUP_DIR."; return 1; }
    log_ok "Backup: $ultimo"
}

instalar_backup() {
    section "Backup diario"
    as_root install -d -o root -g root -m 755 "$INSTALL_DIR/scripts"
    # De root: el usuario de servicio lo ejecuta desde el timer, pero no puede modificarlo.
    as_root install -o root -g root -m 755 "$BACKUP_SCRIPT" "$INSTALL_DIR/scripts/backup-db.sh"
    as_root install -d -o "$APP_USER" -g "$APP_GROUP" -m 700 "$BACKUP_DIR"  # datos de salud de menores
    as_root install -o root -g root -m 644 "$BACKUP_UNIT" /etc/systemd/system/ctnapi-backup.service
    as_root install -o root -g root -m 644 "$BACKUP_TIMER" /etc/systemd/system/ctnapi-backup.timer
    as_root systemctl daemon-reload
    if as_root test -r "$BACKUP_CNF"; then
        as_root systemctl enable --now ctnapi-backup.timer >/dev/null
        log_ok "Timer de backup activo ($(as_root systemctl show -p NextElapseUSecRealtime --value ctnapi-backup.timer 2>/dev/null || echo '?'))."
    else
        log_warn "Falta $BACKUP_CNF (ver deploy/backup.cnf.example): el timer de backup queda sin activar."
    fi
}

aplicar_migraciones() {
    section "Migraciones"
    revisar_migraciones || return $?
    (( ${#PENDIENTES[@]} )) || return 0

    if ! interactivo && [[ "$APLICAR_MIGRACIONES" != "1" ]]; then
        log_err "Hay migraciones pendientes. Corré ./deploy.sh en una terminal o con APLICAR_MIGRACIONES=1."
        return 1
    fi
    if interactivo && ! confirmar "¿Aplicar ${#PENDIENTES[@]} migración(es) en $(db_name) (antes se hace backup)?"; then
        log_err "Migraciones no aplicadas: el deploy no sigue porque el código nuevo puede necesitarlas."
        return 1
    fi
    if ! backup_ahora; then
        confirmar "No se pudo hacer backup. ¿Aplicar igual, SIN backup?" || return 1
        log_warn "Aplicando migraciones sin backup previo."
    fi
    local archivo
    for archivo in "${PENDIENTES[@]}"; do
        log "Aplicando $archivo"
        if ! sql < "$MIGRATIONS_DIR/$archivo"; then
            log_err "Falló $archivo. Las anteriores quedaron aplicadas; esta no se registra. Revisá y reintentá."
            return 1
        fi
        registrar_migracion "$archivo" "$(sha_de "$archivo")" aplicada
        log_ok "$archivo"
    done
}

# Marca como aplicadas (sin ejecutarlas) las migraciones que ya se corrieron a mano. Uso único al adoptar el registro.
registrar_migraciones() {
    local hasta="${1:-}"
    section "Registrar migraciones ya aplicadas"
    asegurar_root
    crear_tabla_migraciones
    revisar_migraciones || true
    local a_marcar=() archivo
    for archivo in "${PENDIENTES[@]}"; do
        if [[ -z "$hasta" || "$(printf '%s\n%s\n' "${archivo%%_*}" "$hasta" | sort -V | head -n 1)" == "${archivo%%_*}" ]]; then
            a_marcar+=("$archivo")
        fi
    done
    (( ${#a_marcar[@]} )) || { log_ok "No hay nada para registrar."; return 0; }
    printf '  Se van a registrar como YA APLICADAS (no se ejecutan):\n'
    printf '    %s\n' "${a_marcar[@]}"
    if interactivo; then
        confirmar "¿Confirmás que estas migraciones ya están aplicadas en $(db_name)?" || { log_warn "Cancelado."; return 1; }
    elif [[ "${CONFIRMAR:-}" != "1" ]]; then
        fail "Sin terminal: repetir con CONFIRMAR=1."
    fi
    for archivo in "${a_marcar[@]}"; do
        registrar_migracion "$archivo" "$(sha_de "$archivo")" registrada
    done
    log_ok "${#a_marcar[@]} migración(es) registrada(s)."
}

# ---------- deploy ----------
rollback_jar() {
    local backup_jar="$1"
    log_warn "Restaurando el JAR anterior."
    if as_root test -s "$backup_jar"; then
        local restore_tmp
        restore_tmp="$(as_root mktemp "$INSTALL_DIR/.${JAR_NAME}.restore.XXXXXX")"
        as_root cp -p "$backup_jar" "$restore_tmp"
        as_root mv -f "$restore_tmp" "$INSTALL_DIR/$JAR_NAME"
        as_root systemctl restart "$SERVICE_NAME" || true
    else
        as_root rm -f "$INSTALL_DIR/$JAR_NAME"
        as_root systemctl stop "$SERVICE_NAME" || true
    fi
    log_warn "Las migraciones aplicadas en este deploy NO se revierten (son aditivas)."
    show_service_logs
}

verificar_requisitos() {
    section "Verificaciones"
    local command
    for command in git mvn java javac curl systemctl install sha256sum; do
        command -v "$command" >/dev/null 2>&1 || fail "No se encontró '$command'. Instalalo antes de desplegar."
    done
    db_client >/dev/null
    asegurar_root

    [[ -d "$REPO_DIR/.git" ]] || fail "No se encontró un repositorio git en $REPO_DIR."
    [[ -f "$REPO_DIR/pom.xml" ]] || fail "No se encontró pom.xml en $REPO_DIR."
    local f
    for f in "$SERVICE_UNIT" "$BACKUP_SCRIPT" "$BACKUP_UNIT" "$BACKUP_TIMER"; do
        [[ -f "$f" ]] || fail "No se encontró $f."
    done
    as_root test -f "$ENV_FILE" || fail "Falta $ENV_FILE. Crealo desde deploy/ctnapi.env.example y configurá sus secretos."
    id "$APP_USER" >/dev/null 2>&1 || fail "No existe el usuario de servicio '$APP_USER'."
    getent group "$APP_GROUP" >/dev/null 2>&1 || fail "No existe el grupo de servicio '$APP_GROUP'."

    local javac_version javac_major
    javac_version="$(javac -version 2>&1 | awk '{print $2}')"
    javac_major="${javac_version%%.*}"
    [[ "$javac_major" =~ ^[0-9]+$ ]] && (( javac_major >= 17 )) \
        || fail "Se requiere JDK 17 o superior para compilar; javac encontrado: ${javac_version:-desconocido}."

    [[ -z "$(git -C "$REPO_DIR" status --porcelain)" ]] \
        || fail "El repositorio tiene cambios locales. Guardalos o descartalos antes de actualizar y desplegar."
    log_ok "Requisitos OK"
}

actualizar() {
    verificar_requisitos

    section "Código fuente"
    local antes despues
    antes="$(git -C "$REPO_DIR" rev-parse --short HEAD)"
    git -C "$REPO_DIR" pull --ff-only
    despues="$(git -C "$REPO_DIR" rev-parse --short HEAD)"
    if [[ "$antes" == "$despues" ]]; then log_ok "Sin cambios nuevos ($despues)"; else
        log_ok "$antes → $despues"
        git -C "$REPO_DIR" log --oneline "$antes..$despues" | sed 's/^/    /'
    fi

    section "Pruebas y compilación"
    mvn -q -f "$REPO_DIR/pom.xml" clean verify || fail "Fallaron las pruebas o la compilación; no se desplegó nada."
    local built_jar="$REPO_DIR/target/$JAR_NAME"
    [[ -s "$built_jar" ]] || fail "No se generó el artefacto esperado: $built_jar."
    log_ok "$(du -h "$built_jar" | cut -f1) $built_jar"

    instalar_backup

    local rc=0
    aplicar_migraciones || rc=$?
    if (( rc == 2 )); then fail "Registrá las migraciones antes de desplegar."; elif (( rc )); then fail "Deploy cancelado."; fi

    section "Despliegue"
    as_root install -d -o "$APP_USER" -g "$APP_GROUP" -m 755 "$INSTALL_DIR"
    local backup_jar new_jar
    backup_jar="$(as_root mktemp "$INSTALL_DIR/.${JAR_NAME}.previous.XXXXXX")"
    if as_root test -s "$INSTALL_DIR/$JAR_NAME"; then
        as_root cp -p "$INSTALL_DIR/$JAR_NAME" "$backup_jar"
    fi
    new_jar="$(as_root mktemp "$INSTALL_DIR/.${JAR_NAME}.new.XXXXXX")"
    as_root install -o "$APP_USER" -g "$APP_GROUP" -m 644 "$built_jar" "$new_jar"
    as_root mv -f "$new_jar" "$INSTALL_DIR/$JAR_NAME"
    as_root install -o root -g root -m 644 "$SERVICE_UNIT" "/etc/systemd/system/$SERVICE_NAME.service"
    as_root systemctl daemon-reload
    as_root systemctl enable "$SERVICE_NAME" >/dev/null 2>&1
    if ! as_root systemctl restart "$SERVICE_NAME"; then
        rollback_jar "$backup_jar"; as_root rm -f "$backup_jar"
        fail "No se pudo reiniciar $SERVICE_NAME."
    fi
    log_ok "Servicio reiniciado"

    log "Esperando hasta ${STARTUP_TIMEOUT_SECONDS}s a que el servicio quede activo."
    local waited=0
    while (( waited < STARTUP_TIMEOUT_SECONDS )); do
        as_root systemctl is-active --quiet "$SERVICE_NAME" && break
        sleep 1
        ((waited += 1))
    done
    if ! as_root systemctl is-active --quiet "$SERVICE_NAME"; then
        rollback_jar "$backup_jar"; as_root rm -f "$backup_jar"
        fail "$SERVICE_NAME no quedó activo."
    fi

    local attempt=1
    while (( attempt <= HEALTHCHECK_RETRIES )); do
        if curl --fail --silent --max-time 5 "$HEALTHCHECK_URL" >/dev/null; then
            as_root rm -f "$backup_jar"
            log_ok "Health check OK. Deploy terminado ($despues)."
            return 0
        fi
        log_warn "Health check: intento ${attempt}/${HEALTHCHECK_RETRIES}"
        sleep "$HEALTHCHECK_DELAY_SECONDS"
        ((attempt += 1))
    done

    rollback_jar "$backup_jar"; as_root rm -f "$backup_jar"
    fail "El health check no respondió correctamente: $HEALTHCHECK_URL."
}

# ---------- estado ----------
estado() {
    section "Estado de $SERVICE_NAME"
    local servicio http version
    servicio="$(systemctl is-active "$SERVICE_NAME" 2>/dev/null || true)"
    http="$(curl -fsS --max-time 3 "$HEALTHCHECK_URL" 2>/dev/null || printf 'sin respuesta')"
    version="$(git -C "$REPO_DIR" log -1 --format='%h %s (%cr)' 2>/dev/null || echo '?')"
    printf '  Servicio : %s\n  Health   : %s  (%s)\n  Repo     : %s\n' "${servicio:-desconocido}" "$http" "$HEALTHCHECK_URL" "$version"
    printf '  Backup   : timer %s, último: %s\n' \
        "$(systemctl is-active ctnapi-backup.timer 2>/dev/null || true)" \
        "$(as_root find "$BACKUP_DIR" -maxdepth 1 -name 'ctnapi-*.sql.gz' -printf '%TF %TH:%TM  %f\n' 2>/dev/null | sort | tail -n 1 || echo '-')"
    printf '  Sync SCA : %s   Escritura Core: %s\n' \
        "$(as_root sed -n 's/^CORE_SYNC_ENABLED=//p' "$ENV_FILE" 2>/dev/null || echo '?')" \
        "$(as_root sed -n 's/^CORE_WRITE_ENABLED=//p' "$ENV_FILE" 2>/dev/null | grep . || echo 'false (por defecto)')"
    echo
    df -h "$INSTALL_DIR" 2>/dev/null | sed 's/^/  /' || true
    echo
    printf '  Últimos errores:\n'
    as_root journalctl -u "$SERVICE_NAME" -p err -n 5 --no-pager -q 2>/dev/null | sed 's/^/    /' || true
    section "Migraciones"
    revisar_migraciones || true
}

menu() {
    while true; do
        printf '\n%s\n' "${C_BOLD}ctnapi · deploy${C_RESET}  ${C_GRAY}($REPO_DIR)${C_RESET}"
        cat <<MENU
  1) Actualizar y desplegar (pull + pruebas + migraciones + deploy)
  2) Estado (servicio, health, backup, migraciones)
  3) Aplicar migraciones pendientes
  4) Registrar migraciones ya aplicadas a mano
  5) Backup ahora
  6) Logs en vivo (Ctrl+C para volver)
  7) Salir
MENU
        local op
        read -r -p "  Opción [1-7]: " op
        case "$op" in
            1) ( actualizar ) || true ;;
            2) ( estado ) || true ;;
            3) ( asegurar_root; aplicar_migraciones ) || true ;;
            4) local hasta; read -r -p "  Registrar hasta el número (vacío = todas): " hasta; ( registrar_migraciones "$hasta" ) || true ;;
            5) ( asegurar_root; backup_ahora ) || true ;;
            6) as_root journalctl -u "$SERVICE_NAME" -f -n 50 || true ;;
            7) return 0 ;;
            *) echo "  Opción inválida." ;;
        esac
    done
}

ayuda() {
    cat <<AYUDA
Uso: ./deploy.sh [acción]

  (sin argumentos)            En una terminal abre el menú; sin terminal hace --update.
  --update                    pull + pruebas + backup diario + migraciones + deploy con health check y rollback
  --estado                    Servicio, health, backup, sync y migraciones
  --migraciones               Lista migraciones aplicadas / pendientes
  --aplicar-migraciones       Backup y aplicación de las pendientes (como root)
  --registrar-migraciones [N] Marca como ya aplicadas (sin correrlas) las pendientes hasta N
                              (uso único al adoptar el registro; sin N, todas)
  --backup                    Backup inmediato de la base
  --instalar-backup           Instala script, servicio y timer del backup diario
  --logs                      journalctl -f del servicio
  --help                      Esta ayuda

Variables: HEALTHCHECK_URL, DB_ADMIN_ARGS (args extra del cliente mariadb para migraciones),
APLICAR_MIGRACIONES=1 (aplicar sin terminal), CONFIRMAR=1 (registrar sin terminal), DEPLOY_LOG.
Log: $DEPLOY_LOG
AYUDA
}

_archivo "---- deploy.sh ${*:-(sin argumentos)} por ${SUDO_USER:-$USER}"
case "${1:-}" in
    --update)                 actualizar ;;
    --estado|--health)        estado ;;
    --migraciones)            asegurar_root; section "Migraciones"; revisar_migraciones || true ;;
    --aplicar-migraciones)    asegurar_root; aplicar_migraciones ;;
    --registrar-migraciones)  registrar_migraciones "${2:-}" ;;
    --backup)                 asegurar_root; backup_ahora ;;
    --instalar-backup)        asegurar_root; instalar_backup ;;
    --logs)                   as_root journalctl -u "$SERVICE_NAME" -f -n 50 ;;
    --help|-h)                ayuda ;;
    "")                       if interactivo; then menu; else actualizar; fi ;;
    *)                        log_err "Acción desconocida: $1"; ayuda >&2; exit 2 ;;
esac
