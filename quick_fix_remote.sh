#!/bin/bash
# Quick fix script to add is_flexible column on remote database

REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "QUICK FIX: Adding is_flexible column"
echo "=========================================="
echo "Remote Host: $REMOTE_HOST"
echo ""

# Copy SQL script to remote server
scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    quick_fix_add_is_flexible.sql ${REMOTE_USER}@${REMOTE_HOST}:/tmp/

# Run the fix on remote server
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${REMOTE_USER}@${REMOTE_HOST} << EOFSSH
    export PGPASSWORD='${DB_PASSWORD}'
    echo "Adding is_flexible column..."
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -f /tmp/quick_fix_add_is_flexible.sql
EOFSSH

echo ""
echo "✅ Quick fix applied!"

