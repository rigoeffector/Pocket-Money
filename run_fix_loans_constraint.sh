#!/bin/bash
# Quick fix script to update loans status constraint
# Run this to fix the constraint issue immediately

# Database connection details
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="pocketmoney_db"
DB_USER="postgres"
DB_PASSWORD="amazimeza12QW!@"

echo "=========================================="
echo "FIXING LOANS STATUS CONSTRAINT"
echo "=========================================="
echo ""

# Run the fix SQL
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" << EOF
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

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "✅ Constraint fixed successfully!"
    echo "=========================================="
else
    echo ""
    echo "=========================================="
    echo "❌ ERROR: Failed to fix constraint!"
    echo "=========================================="
    exit 1
fi

