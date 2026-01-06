#!/bin/bash
# Script to set all receivers to NON-FLEXIBLE mode on remote database

# Configuration
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "SETTING ALL RECEIVERS TO NON-FLEXIBLE MODE (REMOTE)"
echo "=========================================="
echo "Remote Host: $REMOTE_HOST"
echo "Database: $DB_NAME"
echo ""

# Copy SQL script to remote server
echo "📤 Copying SQL script to remote server..."
scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    set_all_receivers_non_flexible.sql ${REMOTE_USER}@${REMOTE_HOST}:/tmp/

if [ $? -ne 0 ]; then
    echo "Failed to upload SQL script. Aborting."
    exit 1
fi

# Run the SQL script on remote server
echo "🔧 Running SQL script on remote server..."
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${REMOTE_USER}@${REMOTE_HOST} << EOFSSH
    export PGPASSWORD='${DB_PASSWORD}'
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -f /tmp/set_all_receivers_non_flexible.sql
EOFSSH

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "✅ All receivers set to NON-FLEXIBLE mode on remote server!"
    echo "=========================================="
    
    # Clean up
    echo "Removing temporary SQL script from remote server..."
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${REMOTE_USER}@${REMOTE_HOST} "rm /tmp/set_all_receivers_non_flexible.sql"
else
    echo ""
    echo "=========================================="
    echo "❌ ERROR: Failed to update receivers on remote server!"
    echo "=========================================="
    exit 1
fi

