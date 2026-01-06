-- Migration script to set all existing receivers to NON-FLEXIBLE mode (is_flexible = false)
-- This ensures all existing receivers default to NON-FLEXIBLE mode

BEGIN;

-- Update all existing receivers to be NON-FLEXIBLE
UPDATE receivers
SET is_flexible = false
WHERE is_flexible IS NULL OR is_flexible = true;

-- Verify the update
SELECT 
    COUNT(*) AS total_receivers,
    COUNT(*) FILTER (WHERE is_flexible = false) AS non_flexible_count,
    COUNT(*) FILTER (WHERE is_flexible = true) AS flexible_count
FROM receivers;

COMMIT;

