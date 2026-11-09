#!/usr/bin/env bash
# Go back to a tagged version AND the matching database backup: ./rollback.sh <tag> <backup-file.sql.gz>
# Orders taken after that backup are lost (the restore script keeps a safety copy of the current database).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/db-backup/lib.sh"
load_config

TAG="${1:-}"; FILE="${2:-}"
[[ -n "$TAG" && -n "$FILE" ]] || { echo "Usage: $0 <tag> <backup-file.sql.gz>" >&2; exit 1; }
cd "$REPO_DIR"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "tag not found: $TAG"
[[ -f "$FILE" ]] || die "backup file not found: $FILE"

echo "Rollback to code $TAG and database $FILE"
echo "Everything saved after that backup is lost. The current database is saved first as a safety copy."
read -r -p "Type ROLLBACK to continue: " ANSWER
[[ "$ANSWER" == "ROLLBACK" ]] || { echo "Cancelled."; exit 1; }

# 1. Database (asks for its own RESTORE confirmation, takes a safety backup, leaves the backend stopped if it fails)
"$SCRIPT_DIR/restore-db.sh" "$FILE"

# 2. Code: old version, rebuilt and started
git checkout "$TAG"
compose up -d --build
log "ROLLBACK DONE: code at $TAG. The repo is on a detached tag: run 'git checkout main' before the next deploy (deploy.sh does it)."
