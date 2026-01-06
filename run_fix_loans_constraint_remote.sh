#!/bin/bash
# Quick fix script to update loans status constraint on remote server
# Run this to fix the constraint issue immediately on remote database

# Configuration
REMOTE_HOST="164.92.89.74"
REMOTE_USER="root"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "FIXING LOANS STATUS CONSTRAINT (REMOTE)"
echo "=========================================="
echo "Remote Host: $REMOTE_HOST"
echo "Database: $DB_NAME"
echo ""

# Run the fix SQL on remote server
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${REMOTE_USER}@${REMOTE_HOST} << EOFSSH
    export PGPASSWORD='${DB_PASSWORD}'
    psql -h localhost -U ${DB_USER} -d ${DB_NAME} << EOF
-- Drop the existing constraint
ALTER TABLE loans DROP CONSTRAINT IF EXISTS loans_status_check;

-- Add the new constraint with correct status values
ALTER TABLE loans ADD CONSTRAINT loans_status_check 
    CHECK (status IN ('PENDING', 'PARTIALLY_PAID', 'COMPLETED'));

-- Verify the constraint
SELECT conname, pg_get_constraintdef(oid) 
FROM pg_constraint 
WHERE conrelid = 'loans'::regclass 
AND conname = 'loans_status_check';
EOF
EOFSSH

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "✅ Constraint fixed successfully on remote server!"
    echo "=========================================="
else
    echo ""
    echo "=========================================="
    echo "❌ ERROR: Failed to fix constraint on remote server!"
    echo "=========================================="
    exit 1
fi

