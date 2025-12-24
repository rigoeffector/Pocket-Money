#!/bin/bash
# Script to apply database migrations to remote server
# Usage: ./apply_migrations_remote.sh

set -e

# Configuration
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "Applying Database Migrations to Remote Server"
echo "=========================================="
echo ""

# Copy migration script to remote server
echo "📤 Copying migration script to remote server..."
scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    migrate_remote_database.sql ${REMOTE_USER}@${REMOTE_HOST}:/tmp/

# Run migration script on remote server
echo "🔧 Running migration script on remote server..."
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${REMOTE_USER}@${REMOTE_HOST} << EOFSSH
    export PGPASSWORD='${DB_PASSWORD}'
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -f /tmp/migrate_remote_database.sql
    echo ""
    echo "✅ Migration completed successfully!"
    echo ""
    echo "Verifying changes..."
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "\d receivers" | grep -E "(assigned_balance|remaining_balance|discount_percentage|user_bonus_percentage)" || echo "Columns may already exist"
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "\d transactions" | grep -E "(admin_income_amount|discount_amount|user_bonus_amount)" || echo "Columns may already exist"
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "\d balance_assignment_history" || echo "Table may already exist"
EOFSSH

echo ""
echo "=========================================="
echo "✅ Migration script execution completed!"
echo "=========================================="
