-- Migration script to add merchant-specific top-up balance support
-- This adds the momo_account_phone field to receivers and creates the merchant_user_balances table

BEGIN;

-- Add momo_account_phone column to receivers table
ALTER TABLE receivers 
ADD COLUMN IF NOT EXISTS momo_account_phone VARCHAR(20);

-- Create merchant_user_balances table to track merchant-specific balances for users
CREATE TABLE IF NOT EXISTS merchant_user_balances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    receiver_id UUID NOT NULL REFERENCES receivers(id) ON DELETE CASCADE,
    balance DECIMAL(19, 2) NOT NULL DEFAULT 0.00,
    total_topped_up DECIMAL(19, 2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, receiver_id)
);

-- Create index for faster lookups
CREATE INDEX IF NOT EXISTS idx_merchant_user_balances_user_id ON merchant_user_balances(user_id);
CREATE INDEX IF NOT EXISTS idx_merchant_user_balances_receiver_id ON merchant_user_balances(receiver_id);
CREATE INDEX IF NOT EXISTS idx_merchant_user_balances_user_receiver ON merchant_user_balances(user_id, receiver_id);

-- Add comment to explain the table
COMMENT ON TABLE merchant_user_balances IS 'Stores merchant-specific balances for users. When a user tops up from a specific merchant, the balance is stored here and can only be used for payments to that merchant.';

-- Add top_up_type column to transactions table
ALTER TABLE transactions 
ADD COLUMN IF NOT EXISTS top_up_type VARCHAR(20);

-- Add comment to explain the column
COMMENT ON COLUMN transactions.top_up_type IS 'Type of top-up: MOMO, CASH, or LOAN (only for TOP_UP transactions)';

COMMIT;

