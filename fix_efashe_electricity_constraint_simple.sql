-- Quick fix: Drop and recreate constraints with ELECTRICITY
-- Run this directly on your database

-- ===================================================================
-- Fix efashe_settings constraint
-- ===================================================================
-- Step 1: Drop the existing constraint
ALTER TABLE efashe_settings DROP CONSTRAINT IF EXISTS efashe_settings_service_type_check;

-- Step 2: Add the constraint with ELECTRICITY included
ALTER TABLE efashe_settings 
ADD CONSTRAINT efashe_settings_service_type_check 
CHECK (service_type IN ('AIRTIME', 'RRA', 'TV', 'MTN', 'ELECTRICITY'));

-- Step 3: Update the comment
COMMENT ON COLUMN efashe_settings.service_type IS 'Service type: AIRTIME, RRA, TV, MTN, ELECTRICITY';

-- ===================================================================
-- Fix efashe_transactions constraint
-- ===================================================================
-- Step 1: Drop the existing constraint
ALTER TABLE efashe_transactions DROP CONSTRAINT IF EXISTS efashe_transactions_service_type_check;

-- Step 2: Add the constraint with ELECTRICITY included
ALTER TABLE efashe_transactions 
ADD CONSTRAINT efashe_transactions_service_type_check 
CHECK (service_type IN ('AIRTIME', 'RRA', 'TV', 'MTN', 'ELECTRICITY'));

