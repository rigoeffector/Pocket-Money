#!/bin/bash

# Script to test refund functionality by updating transaction CHQ1769018477
# Sets EFASHE status to FAILED while keeping MoPay status as SUCCESS
# Usage: ./test_refund_transaction_local.sh

echo "========================================="
echo "Testing Refund - Update Transaction Status (LOCAL)"
echo "========================================="

# Database connection details (LOCAL)
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"

# Read password from user
echo "Enter PostgreSQL password for user '$DB_USER':"
read -s DB_PASSWORD

# Execute SQL script
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -f test_refund_transaction.sql

if [ $? -eq 0 ]; then
    echo "✅ Transaction updated successfully for refund testing"
    echo ""
    echo "Now you can test the refund by calling:"
    echo "GET /api/efashe/status/CHQ1769018477"
else
    echo "❌ Failed to update transaction"
    exit 1
fi
