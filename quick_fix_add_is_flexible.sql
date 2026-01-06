-- Quick fix: Add is_flexible column to receivers table
-- Run this immediately to fix the error

BEGIN;

-- Add is_flexible column if it doesn't exist
ALTER TABLE receivers
ADD COLUMN IF NOT EXISTS is_flexible BOOLEAN NOT NULL DEFAULT false;

-- Set all existing receivers to NON-FLEXIBLE mode
UPDATE receivers
SET is_flexible = false
WHERE is_flexible IS NULL;

COMMIT;

-- Verify
SELECT 
    COUNT(*) AS total_receivers,
    COUNT(*) FILTER (WHERE is_flexible = false) AS non_flexible_count,
    COUNT(*) FILTER (WHERE is_flexible = true) AS flexible_count
FROM receivers;

