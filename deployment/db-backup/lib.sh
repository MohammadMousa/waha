#!/usr/bin/env bash
# Shared helpers for backup-db.sh and restore-db.sh. Sourced, not run.
# Everything lives under deployment/db-backup/ so it can be deleted when Waha moves to RDS.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$REPO_DIR/docker-compose.yml"
ENV_FILE="$REPO_DIR/.env"
DB_SERVICE="mysql"     # service name in docker-compose.yml
APP_SERVICE="waha"     # backend service in docker-compose.yml

# Cron has a minimal PATH.
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

# Read only DB_BACKUP_* from .env (never source the whole file). Real environment wins.
load_config() {
    if [[ -f "$ENV_FILE" ]]; then
        local line key val
        while IFS= read -r line; do
            key="${line%%=*}"; val="${line#*=}"
            val="${val%\"}"; val="${val#\"}"; val="${val%\'}"; val="${val#\'}"
            [[ -z "${!key+x}" ]] && export "$key=$val"
        done < <(grep -E '^DB_BACKUP_[A-Z_]+=' "$ENV_FILE" || true)
    fi
    : "${DB_BACKUP_ENABLED:=true}"
    : "${DB_BACKUP_DIR:=/backups/waha}"
    : "${DB_BACKUP_RETENTION_DAYS:=5}"
    : "${DB_BACKUP_TIME:=01:00}"   # UTC
    : "${DB_BACKUP_MIN_FREE_MB:=1024}"
    LOG_FILE="$DB_BACKUP_DIR/backup.log"
}

if docker compose version >/dev/null 2>&1; then
    compose() { docker compose -f "$COMPOSE_FILE" --project-directory "$REPO_DIR" "$@"; }
else
    compose() { docker-compose -f "$COMPOSE_FILE" --project-directory "$REPO_DIR" "$@"; }
fi

log() {
    local msg; msg="$(date '+%Y-%m-%d %H:%M:%S') [$(basename "$0")] $*"
    echo "$msg"
    [[ -n "${LOG_FILE:-}" && -d "$(dirname "$LOG_FILE")" ]] && echo "$msg" >> "$LOG_FILE" || true
}

die() { log "ERROR: $*"; exit 1; }

prepare_dir() {
    umask 077
    if [[ ! -d "$DB_BACKUP_DIR" ]]; then
        mkdir -p "$DB_BACKUP_DIR" 2>/dev/null \
            || die "cannot create $DB_BACKUP_DIR (run once: sudo mkdir -p $DB_BACKUP_DIR && sudo chown $(id -un): $DB_BACKUP_DIR)"
    fi
    chmod 700 "$DB_BACKUP_DIR" 2>/dev/null || true
    [[ -w "$DB_BACKUP_DIR" ]] || die "$DB_BACKUP_DIR is not writable by $(id -un)"
}

# One lock shared by backup and restore: they can never run at the same time.
take_lock() {
    command -v flock >/dev/null || die "flock is required (package util-linux)"
    exec 9>"$DB_BACKUP_DIR/.lock"
    flock -n 9 || die "another backup or restore is already running"
}

require_db() {
    command -v docker >/dev/null || die "docker not found"
    [[ -f "$COMPOSE_FILE" ]] || die "compose file not found: $COMPOSE_FILE"
    compose exec -T "$DB_SERVICE" true </dev/null >/dev/null 2>&1 \
        || die "MySQL container (service '$DB_SERVICE') is not running"
}

# Credentials come from the container's own environment; they never appear in a command line or log.
db_exec() {
    compose exec -T "$DB_SERVICE" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec "$@"' sh "$@"
}

db_name() { compose exec -T "$DB_SERVICE" sh -c 'printf %s "$MYSQL_DATABASE"' </dev/null; }

# Free space check. Needed = 1.5 x newest backup, else database size on disk; never below the minimum.
check_disk_space() {
    local newest need_kb db_kb free_kb min_kb=$(( DB_BACKUP_MIN_FREE_MB * 1024 ))
    newest="$(ls -1t "$DB_BACKUP_DIR"/waha_*.sql.gz 2>/dev/null | head -n1 || true)"
    if [[ -n "$newest" ]]; then
        need_kb=$(( $(stat -c %s "$newest") / 1024 * 3 / 2 ))
    else
        db_kb="$(db_exec mysql -N -e "SELECT COALESCE(SUM(data_length+index_length),0)/1024 FROM information_schema.tables WHERE table_schema='$(db_name)'" </dev/null 2>/dev/null | cut -d. -f1)"
        need_kb="${db_kb:-0}"
    fi
    (( need_kb < min_kb )) && need_kb=$min_kb
    free_kb="$(df -Pk "$DB_BACKUP_DIR" | awk 'NR==2{print $4}')"
    if (( free_kb < need_kb )); then
        die "not enough disk space in $DB_BACKUP_DIR: ${free_kb} KB free, ${need_kb} KB needed. Nothing was deleted."
    fi
    log "disk check ok: $((free_kb/1024)) MB free, $((need_kb/1024)) MB needed"
}

# Dump to a temp file, validate, then rename. Prints the final path on success.
# $1 = final file name (inside DB_BACKUP_DIR)
make_backup() {
    local final="$DB_BACKUP_DIR/$1" tmp="$DB_BACKUP_DIR/.$1.tmp" db
    db="$(db_name)"
    [[ -n "$db" ]] || die "could not read database name from the MySQL container"
    trap 'rm -f "$tmp"' EXIT
    set -o pipefail
    db_exec mysqldump -uroot --single-transaction --routines --triggers --events \
        --default-character-set=utf8mb4 "$db" </dev/null | gzip -c > "$tmp" \
        || { rm -f "$tmp"; die "mysqldump failed"; }
    validate_backup "$tmp" || { rm -f "$tmp"; die "backup validation failed"; }
    mv "$tmp" "$final"
    trap - EXIT
    log "backup written: $final ($(du -h "$final" | cut -f1))"
}

validate_backup() {
    gzip -t "$1" 2>/dev/null || return 1
    # mysqldump ends a complete dump with "-- Dump completed".
    gzip -dc "$1" | tail -n 5 | grep -q 'Dump completed'
}
