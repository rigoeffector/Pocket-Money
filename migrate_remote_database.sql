-- ===================================================================
-- Complete Database Migration Script for Remote Server
-- Run this script on the production PostgreSQL database
-- ===================================================================
-- Database: pocketmoney_db
-- Run as: postgres user or database owner
-- ===================================================================

BEGIN;

-- ===================================================================
-- 1. Add balance and discount columns to receivers table
-- ===================================================================
ALTER TABLE receivers 
    ADD COLUMN IF NOT EXISTS assigned_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS remaining_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discount_percentage NUMERIC(5, 2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS user_bonus_percentage NUMERIC(5, 2) DEFAULT 0;

COMMENT ON COLUMN receivers.assigned_balance IS 'Total balance assigned to receiver by admin';
COMMENT ON COLUMN receivers.remaining_balance IS 'Balance remaining after payments and discounts';
COMMENT ON COLUMN receivers.discount_percentage IS 'Discount percentage (0-100) applied to payments';
COMMENT ON COLUMN receivers.user_bonus_percentage IS 'User bonus percentage (0-100) credited back to users';

-- ===================================================================
-- 2. Add discount, bonus, and balance tracking columns to transactions table
-- ===================================================================
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS user_bonus_amount NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS receiver_balance_before NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS receiver_balance_after NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS admin_income_amount NUMERIC(19, 2);

COMMENT ON COLUMN transactions.discount_amount IS 'Discount amount applied to this transaction';
COMMENT ON COLUMN transactions.user_bonus_amount IS 'Bonus amount credited back to user for this transaction';
COMMENT ON COLUMN transactions.receiver_balance_before IS 'Receiver remaining balance before this transaction';
COMMENT ON COLUMN transactions.receiver_balance_after IS 'Receiver remaining balance after this transaction';
COMMENT ON COLUMN transactions.admin_income_amount IS 'Amount that goes to admin as sales/income (e.g., 8% when total charge is 10% and user bonus is 2%)';

-- Create index for faster queries by admin income
CREATE INDEX IF NOT EXISTS idx_transactions_admin_income ON transactions(admin_income_amount) WHERE admin_income_amount IS NOT NULL;

-- ===================================================================
-- 3. Create balance_assignment_history table
-- ===================================================================
CREATE TABLE IF NOT EXISTS balance_assignment_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receiver_id UUID NOT NULL REFERENCES receivers(id) ON DELETE CASCADE,
    assigned_balance NUMERIC(19, 2) NOT NULL,
    previous_assigned_balance NUMERIC(19, 2),
    balance_difference NUMERIC(19, 2),
    assigned_by VARCHAR(255),
    notes VARCHAR(1000),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    approved_by VARCHAR(255),
    approved_at TIMESTAMP,
    mopay_transaction_id VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for faster queries
CREATE INDEX IF NOT EXISTS idx_balance_assignment_history_receiver_id ON balance_assignment_history(receiver_id);
CREATE INDEX IF NOT EXISTS idx_balance_assignment_history_created_at ON balance_assignment_history(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_balance_assignment_history_status ON balance_assignment_history(status);

COMMENT ON TABLE balance_assignment_history IS 'Tracks history of balance assignments to receivers';
COMMENT ON COLUMN balance_assignment_history.receiver_id IS 'Reference to receiver who received the balance';
COMMENT ON COLUMN balance_assignment_history.assigned_balance IS 'New assigned balance amount';
COMMENT ON COLUMN balance_assignment_history.previous_assigned_balance IS 'Previous assigned balance before this assignment';
COMMENT ON COLUMN balance_assignment_history.balance_difference IS 'Difference between new and previous balance';
COMMENT ON COLUMN balance_assignment_history.assigned_by IS 'Username or identifier of who assigned the balance';
COMMENT ON COLUMN balance_assignment_history.notes IS 'Optional notes about the balance assignment';
COMMENT ON COLUMN balance_assignment_history.status IS 'Status of balance assignment: PENDING, APPROVED, or REJECTED';
COMMENT ON COLUMN balance_assignment_history.approved_by IS 'Username or identifier of who approved/rejected the balance assignment';
COMMENT ON COLUMN balance_assignment_history.approved_at IS 'Timestamp when the balance assignment was approved or rejected';
COMMENT ON COLUMN balance_assignment_history.mopay_transaction_id IS 'MoPay transaction ID for balance assignment payment';

-- ===================================================================
-- 4. Initialize existing receivers with default balance values if needed
-- ===================================================================
-- Uncomment the following lines if you want to initialize existing receivers
-- UPDATE receivers 
-- SET remaining_balance = assigned_balance 
-- WHERE remaining_balance = 0 AND assigned_balance > 0;

COMMIT;

-- ===================================================================
-- Migration Complete!
-- ===================================================================
-- Verify the changes by running:
--   \d receivers
--   \d transactions  
--   \d balance_assignment_history
-- ===================================================================

