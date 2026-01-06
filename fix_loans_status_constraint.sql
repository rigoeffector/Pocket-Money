-- Fix loans status check constraint
-- This script updates the check constraint to allow PENDING, PARTIALLY_PAID, and COMPLETED

BEGIN;

-- Drop the existing constraint if it exists
ALTER TABLE loans DROP CONSTRAINT IF EXISTS loans_status_check;

-- Add the new constraint with correct status values
ALTER TABLE loans ADD CONSTRAINT loans_status_check 
    CHECK (status IN ('PENDING', 'PARTIALLY_PAID', 'COMPLETED'));

COMMIT;

