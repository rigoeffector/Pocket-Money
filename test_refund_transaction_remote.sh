#!/bin/bash

# Script to test refund functionality by updating transaction CHQ1769018477 on remote server
# Sets EFASHE status to FAILED while keeping MoPay status as SUCCESS
# Usage: ./test_refund_transaction_remote.sh

echo "========================================="
echo "Testing Refund - Update Transaction Status (REMOTE)"
echo "========================================="

# Remote server details
REMOTE_HOST="164.92.89.74"
REMOTE_USER="rigobert"
DB_NAME="pocketmoney_db"
DB_USER="postgres"

# Copy SQL script to remote server and execute
scp test_refund_transaction.sql $REMOTE_USER@$REMOTE_HOST:/tmp/

ssh $REMOTE_USER@$REMOTE_HOST "cd /tmp && PGPASSWORD=amazimeza12QW!@ psql -h localhost -U $DB_USER -d $DB_NAME -f test_refund_transaction.sql"

if [ $? -eq 0 ]; then
    echo "✅ Transaction updated successfully on remote server for refund testing"
    echo ""
    echo "Now you can test the refund by calling:"
    echo "GET /api/efashe/status/CHQ1769018477"
else
    echo "❌ Failed to update transaction on remote server"
    exit 1
fi
