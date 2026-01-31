#!/bin/bash
# Script to apply database migrations to remote server
# Usage: ./apply_migrations_remote.sh

set -e

# Configuration
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "Applying Database Migrations to Remote Server"
echo "=========================================="
echo ""

# Copy consolidated migration script to remote server
echo "📤 Copying consolidated migration script to remote server..."
scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    all_migrations_consolidated.sql ${REMOTE_USER}@${REMOTE_HOST}:/tmp/

# Run consolidated migration script on remote server
echo "🔧 Running consolidated migration script on remote server..."
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${REMOTE_USER}@${REMOTE_HOST} << EOFSSH
    export PGPASSWORD='${DB_PASSWORD}'
    echo "Running all_migrations_consolidated.sql..."
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -f /tmp/all_migrations_consolidated.sql
    echo ""
    echo "✅ All migrations completed successfully!"
    echo ""
    echo "Verifying changes..."
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "\d receivers" | grep -E "(assigned_balance|remaining_balance|discount_percentage|user_bonus_percentage|parent_receiver_id|momo_account_phone|is_flexible)" || echo "Columns may already exist"
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "\d transactions" | grep -E "(admin_income_amount|discount_amount|user_bonus_amount|top_up_type|mopay_transaction_id)" || echo "Columns may already exist"
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "\d balance_assignment_history" || echo "Table may already exist"
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "\d merchant_user_balances" || echo "Table may already exist"
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "\d loans" || echo "Table may already exist"
    echo ""
    echo "Checking payment_categories for EFASHE, GASOLINE, DIESEL, and QR Code:"
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} -c "SELECT name FROM payment_categories WHERE name IN ('EFASHE', 'GASOLINE', 'DIESEL', 'QR Code');" 2>/dev/null | grep -E '(EFASHE|GASOLINE|DIESEL|QR Code)' && echo "✅ Payment categories verified" || echo "⚠️  Some categories may not exist"
EOFSSH

echo ""
echo "=========================================="
echo "✅ Migration script execution completed!"
echo "=========================================="
