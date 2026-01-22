#!/bin/bash

# Script to set a transaction to FAILED on EFASHE status (remote database)
# Prompts for transaction ID and SSH root password

echo "=========================================="
echo "Setting Transaction to FAILED (Remote Database)"
echo "=========================================="

# Prompt for transaction ID
read -p "Enter Transaction ID: " TRANSACTION_ID

if [ -z "$TRANSACTION_ID" ]; then
    echo "❌ Transaction ID cannot be empty"
    exit 1
fi

echo ""
echo "Transaction ID: $TRANSACTION_ID"
echo ""

# Remote server details (update these as needed)
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
REMOTE_DB_HOST="localhost"
REMOTE_DB_PORT="5432"
REMOTE_DB_NAME="pocketmoney_db"
REMOTE_DB_USER="postgres"

# Prompt for SSH root password
read -sp "Enter SSH root password: " SSH_PASSWORD
echo ""

# Create temporary SQL file with the transaction ID
TEMP_SQL_FILE=$(mktemp)
cat > "$TEMP_SQL_FILE" << EOF
-- Set transaction $TRANSACTION_ID to FAILED on EFASHE status (remote database)
-- This is for testing purposes

-- First, check current status
SELECT 
    transaction_id,
    mopay_transaction_id,
    mopay_status,
    efashe_status,
    amount,
    customer_phone,
    full_amount_phone,
    error_message,
    updated_at
FROM efashe_transactions 
WHERE transaction_id = '$TRANSACTION_ID' OR mopay_transaction_id = '$TRANSACTION_ID';

-- Update transaction to set EFASHE status to FAILED
UPDATE efashe_transactions 
SET 
    efashe_status = 'FAILED',
    error_message = COALESCE(error_message, '') || ' | Test: EFASHE transaction set to FAILED for testing',
    updated_at = NOW()
WHERE transaction_id = '$TRANSACTION_ID' OR mopay_transaction_id = '$TRANSACTION_ID';

-- Verify the update
SELECT 
    transaction_id,
    mopay_transaction_id,
    mopay_status,
    efashe_status,
    amount,
    customer_phone,
    full_amount_phone,
    error_message,
    updated_at
FROM efashe_transactions 
WHERE transaction_id = '$TRANSACTION_ID' OR mopay_transaction_id = '$TRANSACTION_ID';
EOF

echo "=========================================="
echo "Connecting to remote server..."
echo "=========================================="

# Use sshpass to provide password (if available) or use SSH key
# First try with sshpass if available
if command -v sshpass &> /dev/null; then
    sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << ENDSSH
        export PGPASSWORD="amazimeza12QW!@"
        psql -h "$REMOTE_DB_HOST" -p "$REMOTE_DB_PORT" -U "$REMOTE_DB_USER" -d "$REMOTE_DB_NAME" << 'EOFSQL'
$(cat "$TEMP_SQL_FILE")
EOFSQL
        unset PGPASSWORD
ENDSSH
else
    # If sshpass is not available, use expect or prompt for password manually
    echo "Note: sshpass not found. You may need to enter the SSH password manually."
    echo "Or install sshpass: brew install hudochenkov/sshpass/sshpass (macOS) or apt-get install sshpass (Linux)"
    echo ""
    echo "Connecting via SSH (you will be prompted for password)..."
    
    ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << ENDSSH
        export PGPASSWORD="amazimeza12QW!@"
        psql -h "$REMOTE_DB_HOST" -p "$REMOTE_DB_PORT" -U "$REMOTE_DB_USER" -d "$REMOTE_DB_NAME" << 'EOFSQL'
$(cat "$TEMP_SQL_FILE")
EOFSQL
        unset PGPASSWORD
ENDSSH
fi

# Check exit status
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Transaction $TRANSACTION_ID updated successfully"
    echo "   EFASHE Status set to: FAILED"
else
    echo ""
    echo "❌ Error updating transaction"
    rm -f "$TEMP_SQL_FILE"
    exit 1
fi

# Clean up temporary file
rm -f "$TEMP_SQL_FILE"

echo "=========================================="
echo "Update Complete"
echo "=========================================="
