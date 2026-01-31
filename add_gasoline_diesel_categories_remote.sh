#!/bin/bash

# Script to add GASOLINE and DIESEL categories to payment_categories table (remote database)
# Prompts for SSH root password

echo "=========================================="
echo "Adding GASOLINE and DIESEL Categories (Remote Database)"
echo "=========================================="

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

echo "=========================================="
echo "Connecting to remote server..."
echo "=========================================="

# Use sshpass to provide password (if available) or use SSH key
# First try with sshpass if available
if command -v sshpass &> /dev/null; then
    sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no "$REMOTE_USER@$REMOTE_HOST" << ENDSSH
        export PGPASSWORD="amazimeza12QW!@"
        psql -h "$REMOTE_DB_HOST" -p "$REMOTE_DB_PORT" -U "$REMOTE_DB_USER" -d "$REMOTE_DB_NAME" << 'EOFSQL'
-- Add GASOLINE and DIESEL categories to payment_categories table
-- This will only insert if the categories don't already exist

-- Add GASOLINE category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'GASOLINE',
    'Payment category for gasoline purchases',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'GASOLINE'
);

-- Add DIESEL category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'DIESEL',
    'Payment category for diesel purchases',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'DIESEL'
);

-- Add QR Code category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'QR Code',
    'Payment category for QR code payments',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'QR Code'
);
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
-- Add GASOLINE and DIESEL categories to payment_categories table
-- This will only insert if the categories don't already exist

-- Add GASOLINE category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'GASOLINE',
    'Payment category for gasoline purchases',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'GASOLINE'
);

-- Add DIESEL category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'DIESEL',
    'Payment category for diesel purchases',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'DIESEL'
);

-- Add QR Code category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'QR Code',
    'Payment category for QR code payments',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'QR Code'
);
EOFSQL
        unset PGPASSWORD
ENDSSH
fi

# Check exit status
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ GASOLINE and DIESEL categories added successfully"
else
    echo ""
    echo "❌ Error adding categories"
    exit 1
fi

echo "=========================================="
echo "Update Complete"
echo "=========================================="
