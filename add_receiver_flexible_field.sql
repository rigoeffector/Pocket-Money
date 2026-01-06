-- Migration script to add is_flexible field to receivers table
-- This field determines if users can pay without checking receiver's remaining balance

BEGIN;

-- Add is_flexible column to receivers table
ALTER TABLE receivers
ADD COLUMN IF NOT EXISTS is_flexible BOOLEAN NOT NULL DEFAULT false;

-- Add comment to explain the column
COMMENT ON COLUMN receivers.is_flexible IS 'If true, users can pay without checking receiver remaining balance. If false, both user balance and receiver balance are checked.';

-- Set all existing receivers to NON-FLEXIBLE mode (false)
UPDATE receivers
SET is_flexible = false
WHERE is_flexible IS NULL OR is_flexible = true;

COMMIT;

