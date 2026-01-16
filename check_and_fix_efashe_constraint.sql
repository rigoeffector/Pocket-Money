-- Check current constraint definitions
SELECT 
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint 
WHERE conname IN ('efashe_settings_service_type_check', 'efashe_transactions_service_type_check')
ORDER BY conname;

-- ===================================================================
-- Fix efashe_settings constraint
-- ===================================================================
-- Drop the existing constraint
ALTER TABLE efashe_settings DROP CONSTRAINT IF EXISTS efashe_settings_service_type_check;

-- Add the constraint with ELECTRICITY included
ALTER TABLE efashe_settings 
ADD CONSTRAINT efashe_settings_service_type_check 
CHECK (service_type IN ('AIRTIME', 'RRA', 'TV', 'MTN', 'ELECTRICITY'));

-- Update the comment
COMMENT ON COLUMN efashe_settings.service_type IS 'Service type: AIRTIME, RRA, TV, MTN, ELECTRICITY';

-- ===================================================================
-- Fix efashe_transactions constraint
-- ===================================================================
-- Drop the existing constraint
ALTER TABLE efashe_transactions DROP CONSTRAINT IF EXISTS efashe_transactions_service_type_check;

-- Add the constraint with ELECTRICITY included
ALTER TABLE efashe_transactions 
ADD CONSTRAINT efashe_transactions_service_type_check 
CHECK (service_type IN ('AIRTIME', 'RRA', 'TV', 'MTN', 'ELECTRICITY'));

-- ===================================================================
-- Verify the new constraints
-- ===================================================================
SELECT 
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint 
WHERE conname IN ('efashe_settings_service_type_check', 'efashe_transactions_service_type_check')
ORDER BY conname;

