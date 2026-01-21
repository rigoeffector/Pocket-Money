#!/bin/bash

# Script to add token column to efashe_transactions table (REMOTE)
# Usage: ./add_efashe_token_column_remote.sh

echo "========================================="
echo "Adding token column to efashe_transactions (REMOTE)"
echo "========================================="

# Remote server details
REMOTE_HOST="164.92.89.74"
REMOTE_USER="rigobert"
DB_NAME="pocketmoney_db"
DB_USER="postgres"

# Execute SQL migration on remote server
ssh $REMOTE_USER@$REMOTE_HOST "cd /home/rigobert/pocketmoney && PGPASSWORD=amazimeza12QW!@ psql -h localhost -U $DB_USER -d $DB_NAME -f add_efashe_token_column.sql"

if [ $? -eq 0 ]; then
    echo "✅ Migration completed successfully on remote server"
else
    echo "❌ Migration failed on remote server"
    exit 1
fi
