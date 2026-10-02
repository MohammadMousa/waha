#!/usr/bin/env bash
# Installs (or updates) the daily backup cron job for the current user.
# DB_BACKUP_TIME is in UTC; it is converted to this server's local time because cron runs on the server clock.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config

[[ "$DB_BACKUP_TIME" =~ ^([01][0-9]|2[0-3]):([0-5][0-9])$ ]] || { echo "DB_BACKUP_TIME must be HH:MM (UTC)" >&2; exit 1; }
LOCAL="$(date -d "TZ=\"UTC\" $DB_BACKUP_TIME" +%H:%M)"
HH="${LOCAL%%:*}"; MM="${LOCAL##*:}"
MARK="# waha-db-backup"
prepare_dir
CMD="$((10#$MM)) $((10#$HH)) * * * $SCRIPT_DIR/backup-db.sh >> $DB_BACKUP_DIR/cron.log 2>&1 $MARK"
( crontab -l 2>/dev/null | grep -v "$MARK" || true; echo "$CMD" ) | crontab -
echo "Backup time $DB_BACKUP_TIME UTC = $LOCAL on this server ($(date +%Z)). Installed:"
crontab -l | grep "$MARK"
