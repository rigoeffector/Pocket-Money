-- Check current constraint definition
SELECT 
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint 
WHERE conname = 'efashe_settings_service_type_check';

-- Drop the existing constraint
ALTER TABLE efashe_settings DROP CONSTRAINT IF EXISTS efashe_settings_service_type_check;

-- Add the constraint with ELECTRICITY included
ALTER TABLE efashe_settings 
ADD CONSTRAINT efashe_settings_service_type_check 
CHECK (service_type IN ('AIRTIME', 'RRA', 'TV', 'MTN', 'ELECTRICITY'));

-- Verify the new constraint
SELECT 
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint 
WHERE conname = 'efashe_settings_service_type_check';

-- Update the comment
COMMENT ON COLUMN efashe_settings.service_type IS 'Service type: AIRTIME, RRA, TV, MTN, ELECTRICITY';

