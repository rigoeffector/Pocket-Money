-- Migration script to add momo_code field to receivers table
-- This field stores the MoMo merchant code for QR code display

BEGIN;

-- Add momo_code column to receivers table
ALTER TABLE receivers
ADD COLUMN IF NOT EXISTS momo_code VARCHAR(255);

-- Add comment to explain the column
COMMENT ON COLUMN receivers.momo_code IS 'MoMo merchant code for QR code display';

COMMIT;
