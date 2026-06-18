#!/usr/bin/env bash
# ============================================================================
# Daily logical backup of the shared MariaDB schema, with rotation.
# Produces a timestamped, gzip-compressed, consistent dump that can be used to
# roll the database back if data is wrongly modified or deleted.
#
# Usage:
#   DB_HOST=mariadb DB_NAME=bccs_catalog DB_USER=bccs_app DB_PASSWORD=*** \
#     ./backup.sh
#
# Restore (rollback) from a dump:
#   gunzip -c bccs_catalog-2026-06-18_0200.sql.gz | \
#     mariadb -h "$DB_HOST" -u root -p"$ROOT_PASS" "$DB_NAME"
# ============================================================================
set -euo pipefail

DB_HOST="${DB_HOST:-mariadb}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-bccs_catalog}"
DB_USER="${DB_USER:?DB_USER is required}"
DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD is required}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

mkdir -p "$BACKUP_DIR"
TIMESTAMP="$(date +%Y-%m-%d_%H%M)"
OUTFILE="${BACKUP_DIR}/${DB_NAME}-${TIMESTAMP}.sql.gz"

echo "[$(date -Is)] Starting backup of ${DB_NAME} on ${DB_HOST}:${DB_PORT}"

# --single-transaction => consistent snapshot without locking InnoDB tables
# --routines/--triggers/--events => full object set so a restore is complete
mariadb-dump \
  --host="$DB_HOST" \
  --port="$DB_PORT" \
  --user="$DB_USER" \
  --password="$DB_PASSWORD" \
  --single-transaction \
  --quick \
  --routines \
  --triggers \
  --events \
  --default-character-set=utf8mb4 \
  --databases "$DB_NAME" \
  | gzip -9 > "$OUTFILE"

echo "[$(date -Is)] Backup written: ${OUTFILE} ($(du -h "$OUTFILE" | cut -f1))"

# Integrity check: the gzip must be valid
gzip -t "$OUTFILE"
echo "[$(date -Is)] Integrity check passed"

# Rotation: delete dumps older than RETENTION_DAYS
find "$BACKUP_DIR" -name "${DB_NAME}-*.sql.gz" -type f -mtime "+${RETENTION_DAYS}" -print -delete

# Optional: push to object storage for off-site durability (uncomment & configure)
# aws s3 cp "$OUTFILE" "s3://${S3_BUCKET}/db-backups/" --storage-class STANDARD_IA

echo "[$(date -Is)] Backup completed successfully"
