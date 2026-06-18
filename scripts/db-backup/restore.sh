#!/usr/bin/env bash
# Restore (rollback) the shared schema from a chosen backup dump.
# DESTRUCTIVE: overwrites current data in $DB_NAME. Confirm before running.
#
#   ./restore.sh /backups/bccs_catalog-2026-06-18_0200.sql.gz
set -euo pipefail

DUMP_FILE="${1:?Usage: restore.sh <path-to-dump.sql.gz>}"
DB_HOST="${DB_HOST:-mariadb}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-bccs_catalog}"
DB_ADMIN_USER="${DB_ADMIN_USER:-root}"
DB_ADMIN_PASSWORD="${DB_ADMIN_PASSWORD:?DB_ADMIN_PASSWORD is required}"

if [[ ! -f "$DUMP_FILE" ]]; then
  echo "Dump file not found: $DUMP_FILE" >&2
  exit 1
fi

echo "WARNING: this will overwrite data in '${DB_NAME}' on ${DB_HOST}."
read -r -p "Type the database name to confirm: " CONFIRM
[[ "$CONFIRM" == "$DB_NAME" ]] || { echo "Aborted."; exit 1; }

echo "[$(date -Is)] Restoring ${DB_NAME} from ${DUMP_FILE}"
gunzip -c "$DUMP_FILE" | mariadb \
  --host="$DB_HOST" --port="$DB_PORT" \
  --user="$DB_ADMIN_USER" --password="$DB_ADMIN_PASSWORD"

echo "[$(date -Is)] Restore completed. Remember to flush downstream caches:"
echo "  redis-cli -h \$REDIS_HOST FLUSHDB   # so stale cached rows are not served"
