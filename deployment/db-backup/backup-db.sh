#!/usr/bin/env bash
# Waha MySQL backup. Runs from cron or by hand: ./backup-db.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config

if [[ "${DB_BACKUP_ENABLED,,}" == "false" ]]; then
    echo "DB_BACKUP_ENABLED=false - nothing to do"
    exit 0
fi
[[ "$DB_BACKUP_RETENTION_DAYS" =~ ^[0-9]+$ ]] || { echo "DB_BACKUP_RETENTION_DAYS must be a number" >&2; exit 1; }

START=$(date +%s)
prepare_dir
take_lock
log "backup started"
require_db
check_disk_space

make_backup "waha_$(TZ=UTC date '+%Y-%m-%d_%H-%M-%S').sql.gz"

# Retention: only after a validated new backup, only files with the Waha naming pattern.
command find "$DB_BACKUP_DIR" -maxdepth 1 -type f \
    -name 'waha_[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]_[0-9][0-9]-[0-9][0-9]-[0-9][0-9].sql.gz' \
    -mmin +$(( DB_BACKUP_RETENTION_DAYS * 1440 )) -print -delete \
    | while read -r f; do log "retention: removed $(basename "$f")"; done

log "backup finished OK in $(( $(date +%s) - START ))s"
