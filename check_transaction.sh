#!/bin/bash
# Check transaction details on remote server
# Usage: ./check_transaction.sh [transaction_id]
# Example: ./check_transaction.sh POCHCST17699627989216095

set -e

# Configuration
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

TRANSACTION_ID="${1:-POCHCST17699627989216095}"

echo "=========================================="
echo "Checking transaction: $TRANSACTION_ID"
echo "=========================================="

# Query via SSH on remote server
ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" "export PGPASSWORD='$DB_PASSWORD'; \
echo ''; echo '--- EFASHE Transactions ---'; \
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c \"SELECT id, transaction_id, mopay_transaction_id, service_type, customer_phone, customer_account_number, amount, mopay_status, efashe_status, created_at FROM efashe_transactions WHERE transaction_id = '$TRANSACTION_ID' OR mopay_transaction_id = '$TRANSACTION_ID';\" \
&& echo '' && echo '--- Regular Transactions ---' && \
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c \"SELECT t.id, t.mopay_transaction_id, t.amount, t.status, t.transaction_type, t.phone_number, t.created_at, pc.name as category, r.company_name FROM transactions t LEFT JOIN payment_categories pc ON t.payment_category_id = pc.id LEFT JOIN receivers r ON t.receiver_id = r.id WHERE t.mopay_transaction_id = '$TRANSACTION_ID';\" \
; unset PGPASSWORD"

echo ""
echo "=========================================="
echo "Done"
echo "=========================================="
