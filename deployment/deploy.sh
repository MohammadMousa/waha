#!/usr/bin/env bash
# Deploy the latest main on the server: ./deploy.sh   (run as the user that owns the backups, e.g. with sudo)
# Steps: check -> backup age -> tag running version -> pull -> build/start -> health check.
# It never rolls back by itself; on failure it prints the rollback command (rollback.sh).

# deploy.sh pulls the backend code, builds it and starts the containers. With COMPOSE_PROFILES=web, that includes nginx. It doesn't build, upload or replace the dashboard files. Nginx serves whatever is in waha/admin_build/web.

set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/db-backup/lib.sh"
load_config

MAX_BACKUP_HOURS="${MAX_BACKUP_HOURS:-12}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8081/api/config}"
HEALTH_WAIT_SECONDS="${HEALTH_WAIT_SECONDS:-600}"
cd "$REPO_DIR"

# 1. Clean tree
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || die "uncommitted changes in $REPO_DIR; clean them first"
BEFORE="$(git rev-parse --short HEAD)"
log "running version: $BEFORE ($(git log -1 --format=%s))"

# 2. Backup age (the daily backup is used; a fresh one is taken only if it is too old)
NEWEST="$(ls -1t "$DB_BACKUP_DIR"/waha_2*.sql.gz 2>/dev/null | head -n1 || true)"
if [[ -n "$NEWEST" ]]; then
    AGE_H=$(( ( $(date +%s) - $(stat -c %Y "$NEWEST") ) / 3600 ))
    log "newest backup: $NEWEST (${AGE_H}h old)"
fi
if [[ -z "$NEWEST" ]] || (( AGE_H >= MAX_BACKUP_HOURS )); then
    log "backup is missing or older than ${MAX_BACKUP_HOURS}h: taking a fresh one first"
    "$SCRIPT_DIR/backup-db.sh"
    NEWEST="$(ls -1t "$DB_BACKUP_DIR"/waha_2*.sql.gz | head -n1)"
fi
validate_backup "$NEWEST" || die "backup file is damaged: $NEWEST"

# 3. Tag the running version (immutable rollback point)
TAG="prod-$(date -u +%Y-%m-%d-%H%M)"
git tag -a "$TAG" -m "Running before deploy" HEAD
git push origin "$TAG"
log "tagged $TAG"

# 4. Pull and start (never 'down -v')
git checkout main
git pull --ff-only origin main
AFTER="$(git rev-parse --short HEAD)"
log "new version: $AFTER"
if [[ "$BEFORE" == "$AFTER" ]]; then log "nothing new on main; rebuilding anyway"; fi
compose up -d --build

# 5. Health check
log "waiting up to ${HEALTH_WAIT_SECONDS}s for $HEALTH_URL"
OK=0
for ((i = 0; i < HEALTH_WAIT_SECONDS; i += 5)); do
    if [[ "$(curl -s -o /dev/null -w '%{http_code}' "$HEALTH_URL" || true)" == "200" ]]; then OK=1; break; fi
    sleep 5
done
FAILED_MIG="$(db_exec mysql -N -uroot "$(db_name)" -e "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0" </dev/null | tr -d '[:space:]')"
LATEST_MIG="$(db_exec mysql -N -uroot "$(db_name)" -e "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1" </dev/null | tr -d '[:space:]')"

if (( OK == 1 )) && [[ "$FAILED_MIG" == "0" ]]; then
    log "DEPLOY OK: $BEFORE -> $AFTER, latest migration V$LATEST_MIG, rollback point tag: $TAG"
else
    log "DEPLOY FAILED: health ok=$OK, failed migrations=$FAILED_MIG, latest migration V$LATEST_MIG"
    log "To go back: sudo $REPO_DIR/deployment/rollback.sh $TAG $NEWEST"
    exit 1
fi
