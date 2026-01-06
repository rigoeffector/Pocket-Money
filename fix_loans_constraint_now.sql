-- IMMEDIATE FIX: Update loans status constraint
-- Run this directly on your database to fix the constraint issue

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

