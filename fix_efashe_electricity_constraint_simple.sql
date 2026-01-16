-- Quick fix: Drop and recreate constraint with ELECTRICITY
-- Run this directly on your database

-- Step 1: Drop the existing constraint
ALTER TABLE efashe_settings DROP CONSTRAINT IF EXISTS efashe_settings_service_type_check;

-- Step 2: Add the constraint with ELECTRICITY included
ALTER TABLE efashe_settings 
ADD CONSTRAINT efashe_settings_service_type_check 
CHECK (service_type IN ('AIRTIME', 'RRA', 'TV', 'MTN', 'ELECTRICITY'));

-- Step 3: Update the comment
COMMENT ON COLUMN efashe_settings.service_type IS 'Service type: AIRTIME, RRA, TV, MTN, ELECTRICITY';

