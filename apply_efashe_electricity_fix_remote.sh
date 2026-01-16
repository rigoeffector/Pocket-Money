#!/bin/bash
# Script to apply EFASHE ELECTRICITY constraint fix to remote database
# Usage: ./apply_efashe_electricity_fix_remote.sh

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
    fix_efashe_electricity_constraint.sql ${REMOTE_USER}@${REMOTE_HOST}:/tmp/

# Run fix script on remote server
echo "🔧 Applying constraint fix on remote database..."
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${REMOTE_USER}@${REMOTE_HOST} << EOFSSH
    export PGPASSWORD='${DB_PASSWORD}'
    echo "Running fix_efashe_electricity_constraint.sql..."
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -f /tmp/fix_efashe_electricity_constraint.sql
    echo ""
    echo "✅ Constraint fix completed!"
    echo ""
    echo "Verifying constraint..."
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'efashe_settings_service_type_check';"
EOFSSH

echo ""
echo "=========================================="
echo "✅ Fix script execution completed!"
echo "=========================================="

