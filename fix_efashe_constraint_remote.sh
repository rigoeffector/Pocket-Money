#!/bin/bash
# Script to fix EFASHE constraint to include ELECTRICITY on remote server
# Usage: ./fix_efashe_constraint_remote.sh

set -e

# Configuration
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "Fixing EFASHE Settings Constraint for ELECTRICITY"
echo "=========================================="
echo ""

# Copy fix script to remote server
echo "📤 Copying fix script to remote server..."
scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    check_and_fix_efashe_constraint.sql ${REMOTE_USER}@${REMOTE_HOST}:/tmp/

# Run fix script on remote server
echo "🔧 Applying constraint fix on remote database..."
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${REMOTE_USER}@${REMOTE_HOST} << EOFSSH
    export PGPASSWORD='${DB_PASSWORD}'
    echo "Checking current constraint..."
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'efashe_settings_service_type_check';"
    echo ""
    echo "Applying fix..."
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -f /tmp/check_and_fix_efashe_constraint.sql
    echo ""
    echo "✅ Constraint fix completed!"
EOFSSH

echo ""
echo "=========================================="
echo "✅ Fix completed!"
echo "=========================================="

