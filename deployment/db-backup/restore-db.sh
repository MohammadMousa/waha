#!/usr/bin/env bash
# Restore the Waha MySQL database from a backup: ./restore-db.sh /backups/waha/waha_YYYY-MM-DD_HH-mm-ss.sql.gz
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config

FILE="${1:-}"
[[ -n "$FILE" ]] || { echo "Usage: $0 <backup-file.sql.gz>" >&2; exit 1; }
[[ -f "$FILE" ]] || { echo "File not found: $FILE" >&2; exit 1; }
FILE="$(readlink -f "$FILE")"

START=$(date +%s)
prepare_dir
take_lock
require_db
log "validating $FILE"
validate_backup "$FILE" || die "backup file is damaged or incomplete: $FILE"

DB="$(db_name)"
echo
echo "This REPLACES everything in database '$DB' with the contents of:"
echo "  $FILE"
echo "The Waha backend will be stopped during the restore."
read -r -p "Type RESTORE to continue: " ANSWER
[[ "$ANSWER" == "RESTORE" ]] || { echo "Cancelled."; exit 1; }

check_disk_space
SAFETY="waha_pre-restore_$(TZ=UTC date '+%Y-%m-%d_%H-%M-%S').sql.gz"
log "taking safety backup of the current database first"
make_backup "$SAFETY"

log "stopping $APP_SERVICE"
compose stop "$APP_SERVICE" </dev/null >/dev/null
DESTRUCTIVE=0
on_exit() {
    if (( DESTRUCTIVE )); then
        log "RESTORE FAILED after the database was replaced. Backend left stopped. Safety backup: $DB_BACKUP_DIR/$SAFETY"
    else
        compose start "$APP_SERVICE" </dev/null >/dev/null 2>&1 || true
    fi
}
trap on_exit EXIT

DESTRUCTIVE=1
db_exec mysql -uroot -e "DROP DATABASE \`$DB\`; CREATE DATABASE \`$DB\`;" </dev/null
gzip -dc "$FILE" | db_exec mysql -uroot --default-character-set=utf8mb4 "$DB"

TABLES="$(db_exec mysql -N -uroot -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB'" </dev/null | tr -d '[:space:]')"
[[ "$TABLES" =~ ^[0-9]+$ && "$TABLES" -gt 0 ]] || die "restore finished but database has no tables"
log "restore verified: $TABLES tables"
DESTRUCTIVE=0

log "starting $APP_SERVICE"
compose start "$APP_SERVICE" </dev/null >/dev/null
trap - EXIT
log "restore finished OK in $(( $(date +%s) - START ))s. Safety backup kept: $DB_BACKUP_DIR/$SAFETY"
