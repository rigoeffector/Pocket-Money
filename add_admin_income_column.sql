-- Migration script to add admin_income_amount column to transactions table
-- Run this script on your PostgreSQL database

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS admin_income_amount NUMERIC(19, 2);

-- Create index for faster queries by admin income
CREATE INDEX IF NOT EXISTS idx_transactions_admin_income ON transactions(admin_income_amount) WHERE admin_income_amount IS NOT NULL;

COMMENT ON COLUMN transactions.admin_income_amount IS 'Amount that goes to admin as sales/income (e.g., 8% when total charge is 10% and user bonus is 2%)';

