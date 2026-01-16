-- Migration: Add transfer transaction ID columns and initial status columns to efashe_transactions table
-- Date: 2026-01-16
-- Description: Add columns to store unique transaction IDs for each transfer and track initial vs current status

-- Add new columns for individual transfer transaction IDs
ALTER TABLE efashe_transactions 
ADD COLUMN IF NOT EXISTS full_amount_transaction_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS customer_cashback_transaction_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS besoft_share_transaction_id VARCHAR(255);

-- Add columns for initial status tracking
ALTER TABLE efashe_transactions 
ADD COLUMN IF NOT EXISTS initial_mopay_status VARCHAR(50),
ADD COLUMN IF NOT EXISTS initial_efashe_status VARCHAR(50);

-- Add comments
COMMENT ON COLUMN efashe_transactions.full_amount_transaction_id IS 'Unique transaction ID for full amount transfer to full amount phone';
COMMENT ON COLUMN efashe_transactions.customer_cashback_transaction_id IS 'Unique transaction ID for customer cashback transfer';
COMMENT ON COLUMN efashe_transactions.besoft_share_transaction_id IS 'Unique transaction ID for besoft share transfer';
COMMENT ON COLUMN efashe_transactions.initial_mopay_status IS 'Initial MoPay status when transaction was first created';
COMMENT ON COLUMN efashe_transactions.initial_efashe_status IS 'Initial EFASHE status when transaction was first created';

-- Create indexes for faster lookups (optional, but recommended if you'll query by these IDs)
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_full_amount_tx_id ON efashe_transactions(full_amount_transaction_id);
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_customer_cashback_tx_id ON efashe_transactions(customer_cashback_transaction_id);
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_besoft_share_tx_id ON efashe_transactions(besoft_share_transaction_id);

