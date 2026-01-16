-- Migration: Add ELECTRICITY to efashe_settings and efashe_transactions service_type_check constraints
-- This script can be run on both local and remote databases

BEGIN;

-- ===================================================================
-- Fix efashe_settings constraint
-- ===================================================================
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

-- ===================================================================
-- Fix efashe_transactions constraint
-- ===================================================================
-- Drop existing check constraint if it exists
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'efashe_transactions_service_type_check') THEN
        ALTER TABLE efashe_transactions DROP CONSTRAINT efashe_transactions_service_type_check;
        RAISE NOTICE 'Dropped existing efashe_transactions_service_type_check constraint';
    END IF;
END $$;

-- Add check constraint with all service types including ELECTRICITY
ALTER TABLE efashe_transactions 
ADD CONSTRAINT efashe_transactions_service_type_check 
CHECK (service_type IN ('AIRTIME', 'RRA', 'TV', 'MTN', 'ELECTRICITY'));

-- ===================================================================
-- Verify constraints were created
-- ===================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'efashe_settings_service_type_check') THEN
        RAISE NOTICE '✅ Successfully created efashe_settings_service_type_check constraint with ELECTRICITY';
    ELSE
        RAISE EXCEPTION '❌ Failed to create efashe_settings constraint';
    END IF;
    
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'efashe_transactions_service_type_check') THEN
        RAISE NOTICE '✅ Successfully created efashe_transactions_service_type_check constraint with ELECTRICITY';
    ELSE
        RAISE EXCEPTION '❌ Failed to create efashe_transactions constraint';
    END IF;
END $$;

-- Show the constraint definitions
SELECT 
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint 
WHERE conname IN ('efashe_settings_service_type_check', 'efashe_transactions_service_type_check')
ORDER BY conname;

COMMIT;

