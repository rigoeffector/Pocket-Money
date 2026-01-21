#!/bin/bash

# Script to reset transaction CHQ1769018477 for testing (local database)
# Removes REFUND_PROCESSED flag and cleans up duplicate error messages
# Uses credentials from application-dev.properties

echo "=========================================="
echo "Resetting Transaction CHQ1769018477 for Testing"
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
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f reset_transaction_for_testing_local.sql

# Check exit status
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Transaction CHQ1769018477 reset successfully"
    echo "   EFASHE Status: FAILED"
    echo "   MoPay Status: 200"
    echo "   Error message cleaned up"
    echo "   REFUND_PROCESSED flag removed"
else
    echo ""
    echo "❌ Error resetting transaction"
    exit 1
fi

# Unset password
unset PGPASSWORD

echo "=========================================="
echo "Reset Complete"
echo "=========================================="
