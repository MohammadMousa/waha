# Waha MySQL backup & restore

Nightly backup of the containerized MySQL (`mysql` service in `docker-compose.yml`) and a guarded restore.
Self-contained: delete this folder (and the cron lines) when Waha moves to RDS.

## How it works
- `mysqldump` runs **inside** the MySQL container, so it uses the container's own `MYSQL_ROOT_PASSWORD` / `MYSQL_DATABASE`.
  No credentials are stored in these scripts or logs.
- Settings are read from `.env` next to `docker-compose.yml` (only `DB_BACKUP_*` keys). Real environment variables win. All are optional:

| Key | Default | Meaning |
|---|---|---|
| `DB_BACKUP_ENABLED` | `true` | `false` = scripts exit without doing anything |
| `DB_BACKUP_DIR` | `/backups/waha` | host folder (outside docker, outside any web root) |
| `DB_BACKUP_RETENTION_DAYS` | `5` | backups older than this are removed after a good new one |
| `DB_BACKUP_TIME` | `01:00` | daily run time in **UTC** (01:00 UTC = 04:00 Riyadh); used by `install-cron.sh` |
| `DB_BACKUP_MIN_FREE_MB` | `1024` | minimum free space required before a dump |

## One-time setup (on the server)
```bash
sudo mkdir -p /backups/waha && sudo chown $USER: /backups/waha   # the script also tries to create it
cd <waha repo>/deployment/db-backup
chmod 700 *.sh
./backup-db.sh          # first manual run
./install-cron.sh       # installs the daily job for the current user
```
The user must be able to run `docker` (member of the `docker` group). Check with `crontab -l`.

## Manual backup
```bash
./backup-db.sh
```
Creates `waha_YYYY-MM-DD_HH-mm-ss.sql.gz` in `DB_BACKUP_DIR` (name in UTC) with the same checks and retention as the cron run.

Each run: lock -> disk-space check -> dump to a temp file -> gzip test + "Dump completed" check -> rename -> retention.

## Retention
Cleanup happens only after a new backup was written **and validated**. If anything fails, all existing backups are kept and the exit status is non-zero.
Only files named `waha_YYYY-MM-DD_HH-mm-ss.sql.gz` are ever deleted. `waha_pre-restore_*` files are never deleted automatically.

## Disk space
Before dumping, free space in `DB_BACKUP_DIR` must be at least 1.5x the newest backup (or the database size if none yet), and never less than `DB_BACKUP_MIN_FREE_MB`.
If not, the run fails with a clear log line. **Nothing is deleted to make room.**

## Restore
```bash
./restore-db.sh /backups/waha/waha_2026-10-01_04-00-00.sql.gz
```
1. Validates the chosen file.
2. Asks you to type `RESTORE`.
3. Takes a safety backup `waha_pre-restore_<time>.sql.gz` (kept).
4. Stops the `waha` backend (no writes), drops and recreates the database, loads the backup.
5. Checks that tables exist, starts the backend again.

If the restore fails after the database was replaced, the backend is left **stopped** and the log names the safety backup to restore from.
Backup and restore share one lock, so they can never run together.

## Logs
- `DB_BACKUP_DIR/backup.log` - every run (start, disk check, result, duration)
- `DB_BACKUP_DIR/cron.log` - raw cron output

## Troubleshooting
| Symptom | Check |
|---|---|
| `MySQL container ... is not running` | `docker compose ps`; start it with `docker compose up -d mysql` |
| `not enough disk space` | free space on the backup disk, or move `DB_BACKUP_DIR` |
| `another backup or restore is already running` | wait, or check `ps` for a stuck `mysqldump`; the lock frees itself when the process ends |
| `cannot create /backups/waha` | create it once with sudo and `chown` it to the cron user |
| Cron does nothing | `crontab -l` has the `waha-db-backup` lines; user is in the `docker` group; read `cron.log` |
| Wrong run time | `DB_BACKUP_TIME` is UTC; `install-cron.sh` prints the converted server time. Re-run it after changing the value |
| Test a backup file | `gzip -t file && gzip -dc file \| tail -n 3` should show `Dump completed` |
