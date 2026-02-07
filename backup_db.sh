#!/bin/bash
# Create a PostgreSQL dump of pocketmoney_db for backup (keep locally).
# Usage:
#   ./backup_db.sh              # backup from REMOTE server (default)
#   ./backup_db.sh remote       # same
#   ./backup_db.sh local        # backup from local PostgreSQL
#
# Dumps are saved under ./backups/ as pocketmoney_db_YYYYMMDD_HHMMSS.sql
# (and optionally .sql.gz if you have gzip).

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="${SCRIPT_DIR}/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DUMP_NAME="pocketmoney_db_${TIMESTAMP}.sql"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

# Remote server (for remote backup)
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
REMOTE_DB_HOST="localhost"
REMOTE_DB_PORT="5432"

MODE="${1:-remote}"

mkdir -p "$BACKUP_DIR"
DUMP_PATH="${BACKUP_DIR}/${DUMP_NAME}"

echo "=========================================="
echo "PocketMoney DB backup"
echo "=========================================="
echo "Mode:    $MODE"
echo "DB:      $DB_NAME"
echo "Output:  $DUMP_PATH"
echo ""

if [ "$MODE" = "local" ]; then
    DB_HOST="${DB_HOST:-localhost}"
    DB_PORT="${DB_PORT:-5432}"
    echo "Using local PostgreSQL at ${DB_HOST}:${DB_PORT}"
    if ! command -v pg_dump >/dev/null 2>&1; then
        echo "Error: pg_dump not found. Install PostgreSQL client (e.g. brew install libpq)."
        exit 1
    fi
    export PGPASSWORD="$DB_PASSWORD"
    pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
        --no-owner --no-acl \
        -f "$DUMP_PATH"
    unset PGPASSWORD
else
    echo "Using remote server: $REMOTE_USER@$REMOTE_HOST"
    ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" \
        "export PGPASSWORD='$DB_PASSWORD'; pg_dump -h $REMOTE_DB_HOST -p $REMOTE_DB_PORT -U $DB_USER -d $DB_NAME --no-owner --no-acl" \
        > "$DUMP_PATH"
fi

if [ ! -s "$DUMP_PATH" ]; then
    echo "Error: dump file is empty. Check DB connection and credentials."
    rm -f "$DUMP_PATH"
    exit 1
fi

SIZE=$(ls -lh "$DUMP_PATH" | awk '{print $5}')
echo ""
echo "Backup created: $DUMP_PATH ($SIZE)"

# Optional: also create compressed copy to save space
if command -v gzip >/dev/null 2>&1; then
    gzip -c "$DUMP_PATH" > "${DUMP_PATH}.gz"
    echo "Compressed:     ${DUMP_PATH}.gz ($(ls -lh "${DUMP_PATH}.gz" | awk '{print $5}'))"
fi

echo ""
echo "To restore later:"
echo "  psql -U postgres -d pocketmoney_db -f $DUMP_PATH"
if [ -f "${DUMP_PATH}.gz" ]; then
    echo "  or: gunzip -c ${DUMP_PATH}.gz | psql -U postgres -d pocketmoney_db"
fi
echo "=========================================="
