-- Migration script to add country and country_code fields to receivers table
-- These fields store the country name and country code for the receiver

BEGIN;

-- Add country column to receivers table
ALTER TABLE receivers
ADD COLUMN IF NOT EXISTS country VARCHAR(255);

-- Add country_code column to receivers table
ALTER TABLE receivers
ADD COLUMN IF NOT EXISTS country_code VARCHAR(10);

-- Add comments to explain the columns
COMMENT ON COLUMN receivers.country IS 'Country name for the receiver';
COMMENT ON COLUMN receivers.country_code IS 'Country code (e.g., RW, UG, KE) for the receiver';

COMMIT;
