-- Add missing columns to receivers table
-- Run this SQL in your PostgreSQL database (DBeaver or psql)

ALTER TABLE receivers 
ADD COLUMN IF NOT EXISTS wallet_balance DECIMAL(19,2) NOT NULL DEFAULT 0,
ADD COLUMN IF NOT EXISTS total_received DECIMAL(19,2) NOT NULL DEFAULT 0,
ADD COLUMN IF NOT EXISTS last_transaction_date TIMESTAMP;

-- Update existing records to have default values if columns were just added
UPDATE receivers 
SET wallet_balance = 0 
WHERE wallet_balance IS NULL;

UPDATE receivers 
SET total_received = 0 
WHERE total_received IS NULL;

