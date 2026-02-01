#!/bin/bash

# Script to check a transaction on remote server
# Usage: ./check_transaction_remote.sh TRANSACTION_ID

set -e

TRANSACTION_ID="${1:-CUF1769766629}"

# Remote server details
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
REMOTE_DB_HOST="localhost"
REMOTE_DB_PORT="5432"
REMOTE_DB_NAME="pocketmoney_db"
REMOTE_DB_USER="postgres"
REMOTE_DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "Checking Transaction: $TRANSACTION_ID"
echo "=========================================="
echo ""

# Create SQL query
SQL_QUERY=$(cat <<EOFSQL
-- Check EFASHE Transaction
\echo ''
\echo '=== EFASHE TRANSACTION ==='
SELECT 
    transaction_id,
    service_type,
    customer_phone,
    customer_account_number,
    customer_account_name,
    amount,
    currency,
    trx_id,
    mopay_transaction_id,
    mopay_status,
    efashe_status,
    token,
    error_message,
    created_at,
    updated_at
FROM efashe_transactions
WHERE transaction_id = '$TRANSACTION_ID' 
   OR mopay_transaction_id = '$TRANSACTION_ID'
   OR trx_id = '$TRANSACTION_ID';

-- Check Payment Transaction (who paid)
\echo ''
\echo '=== PAYMENT TRANSACTION (WHO PAID) ==='
SELECT 
    t.id,
    t.mopay_transaction_id,
    t.phone_number as payer_phone,
    t.amount,
    t.status,
    t.transaction_type,
    t.created_at,
    u.username as user_username,
    u.phone_number as user_phone,
    u.email as user_email,
    pc.name as payment_category,
    r.company_name as receiver_name
FROM transactions t
LEFT JOIN users u ON t.user_id = u.id
LEFT JOIN payment_categories pc ON t.payment_category_id = pc.id
LEFT JOIN receivers r ON t.receiver_id = r.id
WHERE t.mopay_transaction_id = '$TRANSACTION_ID'
   OR t.id::text = '$TRANSACTION_ID';

-- Check if transaction ID appears in any other field
\echo ''
\echo '=== SEARCHING IN ALL FIELDS ==='
SELECT 
    'efashe_transactions' as table_name,
    transaction_id,
    mopay_transaction_id,
    trx_id
FROM efashe_transactions
WHERE transaction_id LIKE '%$TRANSACTION_ID%'
   OR mopay_transaction_id LIKE '%$TRANSACTION_ID%'
   OR trx_id LIKE '%$TRANSACTION_ID%'
   OR customer_phone LIKE '%$TRANSACTION_ID%';

SELECT 
    'transactions' as table_name,
    mopay_transaction_id,
    id::text as transaction_uuid
FROM transactions
WHERE mopay_transaction_id LIKE '%$TRANSACTION_ID%'
   OR id::text LIKE '%$TRANSACTION_ID%';
EOFSQL
)

# Query the remote database
if command -v sshpass &> /dev/null; then
    read -sp "Enter SSH root password: " SSH_PASSWORD
    echo ""
    echo "$SQL_QUERY" | sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" "export PGPASSWORD='$REMOTE_DB_PASSWORD' && psql -h $REMOTE_DB_HOST -p $REMOTE_DB_PORT -U $REMOTE_DB_USER -d $REMOTE_DB_NAME"
else
    echo "Connecting via SSH (you will be prompted for password)..."
    echo "$SQL_QUERY" | ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" "export PGPASSWORD='$REMOTE_DB_PASSWORD' && psql -h $REMOTE_DB_HOST -p $REMOTE_DB_PORT -U $REMOTE_DB_USER -d $REMOTE_DB_NAME"
fi

echo ""
echo "=========================================="
echo "Query Complete"
echo "=========================================="
