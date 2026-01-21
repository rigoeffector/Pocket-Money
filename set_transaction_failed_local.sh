#!/bin/bash

# Script to set transaction CHQ1769018477 to FAILED on EFASHE status (local database)
# Uses credentials from application-dev.properties

echo "=========================================="
echo "Setting Transaction CHQ1769018477 to FAILED"
echo "Database: Local (localhost:5432/pocketmoney_db)"
echo "=========================================="

# Database credentials from application-dev.properties
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

# Export password for psql
export PGPASSWORD="$DB_PASSWORD"

# Run the SQL script
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f set_transaction_failed_local.sql

# Check exit status
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Transaction CHQ1769018477 updated successfully"
    echo "   EFASHE Status set to: FAILED"
else
    echo ""
    echo "❌ Error updating transaction"
    exit 1
fi

# Unset password
unset PGPASSWORD

echo "=========================================="
echo "Update Complete"
echo "=========================================="
