-- Migration script to add balance and discount columns to receivers table
-- Run this script on your PostgreSQL database

-- Add new columns to receivers table
ALTER TABLE receivers 
    ADD COLUMN IF NOT EXISTS assigned_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS remaining_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discount_percentage NUMERIC(5, 2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS user_bonus_percentage NUMERIC(5, 2) DEFAULT 0;

-- Add new columns to transactions table for tracking discounts and bonuses
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS user_bonus_amount NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS receiver_balance_before NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS receiver_balance_after NUMERIC(19, 2);

-- Set initial remaining_balance to match assigned_balance for existing receivers (if needed)
-- UPDATE receivers SET remaining_balance = assigned_balance WHERE remaining_balance = 0;

COMMENT ON COLUMN receivers.assigned_balance IS 'Total balance assigned to receiver by admin';
COMMENT ON COLUMN receivers.remaining_balance IS 'Balance remaining after payments and discounts';
COMMENT ON COLUMN receivers.discount_percentage IS 'Discount percentage (0-100) applied to payments';
COMMENT ON COLUMN receivers.user_bonus_percentage IS 'User bonus percentage (0-100) credited back to users';

COMMENT ON COLUMN transactions.discount_amount IS 'Discount amount applied to this transaction';
COMMENT ON COLUMN transactions.user_bonus_amount IS 'Bonus amount credited back to user for this transaction';
COMMENT ON COLUMN transactions.receiver_balance_before IS 'Receiver remaining balance before this transaction';
COMMENT ON COLUMN transactions.receiver_balance_after IS 'Receiver remaining balance after this transaction';

