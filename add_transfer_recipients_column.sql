-- Migration: Add transfer_recipients column to transactions table
-- Date: 2026-01-XX
-- Description: Add column to store transfer recipient information (names, phones, amounts) as JSON for PAY_CUSTOMER transactions

-- Add transfer_recipients column to store JSON data
ALTER TABLE transactions 
ADD COLUMN IF NOT EXISTS transfer_recipients TEXT;

-- Add comment
COMMENT ON COLUMN transactions.transfer_recipients IS 'JSON array of transfer recipients with names, phones, and amounts. Format: [{"phone":"250788123456","name":"John Doe","amount":1000}, ...]';

-- Create index for JSON queries (optional, for PostgreSQL JSONB operations if needed later)
-- Note: TEXT column can be queried, but for better performance with JSON queries, consider converting to JSONB later
-- CREATE INDEX IF NOT EXISTS idx_transactions_transfer_recipients ON transactions USING gin((transfer_recipients::jsonb));
