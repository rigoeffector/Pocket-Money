-- Migration: Add ELECTRICITY to efashe_settings_service_type_check constraint
-- This script can be run on both local and remote databases

BEGIN;

-- Drop existing check constraint if it exists
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'efashe_settings_service_type_check') THEN
        ALTER TABLE efashe_settings DROP CONSTRAINT efashe_settings_service_type_check;
        RAISE NOTICE 'Dropped existing efashe_settings_service_type_check constraint';
    END IF;
END $$;

-- Add check constraint with all service types including ELECTRICITY
ALTER TABLE efashe_settings 
ADD CONSTRAINT efashe_settings_service_type_check 
CHECK (service_type IN ('AIRTIME', 'RRA', 'TV', 'MTN', 'ELECTRICITY'));

-- Update comment to include ELECTRICITY
COMMENT ON COLUMN efashe_settings.service_type IS 'Service type: AIRTIME, RRA, TV, MTN, ELECTRICITY';

-- Verify the constraint was created
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'efashe_settings_service_type_check') THEN
        RAISE NOTICE '✅ Successfully created efashe_settings_service_type_check constraint with ELECTRICITY';
    ELSE
        RAISE EXCEPTION '❌ Failed to create constraint';
    END IF;
END $$;

-- Show the constraint definition
SELECT 
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint 
WHERE conname = 'efashe_settings_service_type_check';

COMMIT;

