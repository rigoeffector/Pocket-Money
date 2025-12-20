-- Fix receivers table: Add missing columns
-- Run this in DBeaver SQL Editor connected to pocketmoney_db

-- Add the missing columns
ALTER TABLE receivers 
ADD COLUMN IF NOT EXISTS wallet_balance DECIMAL(19,2) DEFAULT 0 NOT NULL,
ADD COLUMN IF NOT EXISTS total_received DECIMAL(19,2) DEFAULT 0 NOT NULL,
ADD COLUMN IF NOT EXISTS last_transaction_date TIMESTAMP;

-- Update existing records to set default values
UPDATE receivers SET wallet_balance = 0 WHERE wallet_balance IS NULL;
UPDATE receivers SET total_received = 0 WHERE total_received IS NULL;

-- Verify the columns exist
SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_name = 'receivers'
ORDER BY ordinal_position;
