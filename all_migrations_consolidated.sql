-- ===================================================================
-- Consolidated Database Migration Script for Remote Server
-- This script includes ALL migrations in the correct order
-- Run this script on the production PostgreSQL database
-- ===================================================================
-- Database: pocketmoney_db
-- Run as: postgres user or database owner
-- ===================================================================
-- IMPORTANT: This script is IDEMPOTENT - safe to run multiple times
-- All CREATE/ALTER statements use IF NOT EXISTS to prevent errors
-- This script is automatically run on every deployment via deploy.sh
-- ===================================================================

BEGIN;

-- ===================================================================
-- 0. Base schema (for fresh database - no-op if tables already exist)
-- SAFE FOR EXISTING DATA: CREATE TABLE IF NOT EXISTS only runs when the
-- table is missing. If you already have receivers, users, transactions,
-- etc., this block does nothing and your client data is untouched.
-- ===================================================================
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_names VARCHAR(255) NOT NULL,
    phone_number VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(255),
    is_assigned_nfc_card BOOLEAN NOT NULL DEFAULT false,
    nfc_card_id VARCHAR(255) UNIQUE,
    amount_on_card NUMERIC(19, 2) NOT NULL DEFAULT 0,
    amount_remaining NUMERIC(19, 2) NOT NULL DEFAULT 0,
    pin VARCHAR(255) NOT NULL,
    otp VARCHAR(50),
    otp_expires_at TIMESTAMP,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    last_transaction_date TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS receivers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_name VARCHAR(255) NOT NULL,
    manager_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    receiver_phone VARCHAR(50) NOT NULL UNIQUE,
    momo_account_phone VARCHAR(20),
    account_number VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'NOT_ACTIVE',
    email VARCHAR(255),
    address VARCHAR(255),
    description VARCHAR(255),
    wallet_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_received NUMERIC(19, 2) NOT NULL DEFAULT 0,
    assigned_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    remaining_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    discount_percentage NUMERIC(5, 2) DEFAULT 0,
    user_bonus_percentage NUMERIC(5, 2) DEFAULT 0,
    is_flexible BOOLEAN NOT NULL DEFAULT false,
    parent_receiver_id UUID,
    last_transaction_date TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payment_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    payment_category_id UUID REFERENCES payment_categories(id),
    receiver_id UUID REFERENCES receivers(id),
    transaction_type VARCHAR(50) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    mopay_transaction_id VARCHAR(500),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    phone_number VARCHAR(50),
    message VARCHAR(1000),
    balance_before NUMERIC(19, 2),
    balance_after NUMERIC(19, 2),
    discount_amount NUMERIC(19, 2),
    user_bonus_amount NUMERIC(19, 2),
    admin_income_amount NUMERIC(19, 2),
    receiver_balance_before NUMERIC(19, 2),
    receiver_balance_after NUMERIC(19, 2),
    top_up_type VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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
    admin_phone VARCHAR(20),
    receiver_phone VARCHAR(20),
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
-- 4. Add parent_receiver_id column to receivers table for submerchant relationships
-- ===================================================================
ALTER TABLE receivers ADD COLUMN IF NOT EXISTS parent_receiver_id UUID;

-- Add foreign key constraint (drop first if exists to avoid errors)
DO $$ 
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_receivers_parent_receiver') THEN
        ALTER TABLE receivers DROP CONSTRAINT fk_receivers_parent_receiver;
    END IF;
END $$;

ALTER TABLE receivers 
ADD CONSTRAINT fk_receivers_parent_receiver 
FOREIGN KEY (parent_receiver_id) 
REFERENCES receivers(id) 
ON DELETE SET NULL;

-- Add index for better query performance
CREATE INDEX IF NOT EXISTS idx_receivers_parent_receiver_id ON receivers(parent_receiver_id);

COMMENT ON COLUMN receivers.parent_receiver_id IS 'Reference to parent receiver for submerchant relationships. NULL if main merchant.';

-- ===================================================================
-- 5. Make user_id nullable in transactions table for guest MOMO payments
-- ===================================================================
DO $$ 
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'transactions' 
        AND column_name = 'user_id' 
        AND is_nullable = 'NO'
    ) THEN
        ALTER TABLE transactions ALTER COLUMN user_id DROP NOT NULL;
    END IF;
END $$;

COMMENT ON COLUMN transactions.user_id IS 'Reference to user who made the payment. NULL for guest MOMO payments.';

-- ===================================================================
-- 6. Add merchant-specific top-up balance support
-- ===================================================================
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

-- Create indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_merchant_user_balances_user_id ON merchant_user_balances(user_id);
CREATE INDEX IF NOT EXISTS idx_merchant_user_balances_receiver_id ON merchant_user_balances(receiver_id);
CREATE INDEX IF NOT EXISTS idx_merchant_user_balances_user_receiver ON merchant_user_balances(user_id, receiver_id);

COMMENT ON TABLE merchant_user_balances IS 'Stores merchant-specific balances for users. When a user tops up from a specific merchant, the balance is stored here and can only be used for payments to that merchant.';

-- Add top_up_type column to transactions table
ALTER TABLE transactions 
ADD COLUMN IF NOT EXISTS top_up_type VARCHAR(20);

COMMENT ON COLUMN transactions.top_up_type IS 'Type of top-up: MOMO, CASH, or LOAN (only for TOP_UP transactions)';

-- ===================================================================
-- 7. Add loans table for tracking loan transactions
-- ===================================================================
CREATE TABLE IF NOT EXISTS loans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    receiver_id UUID NOT NULL REFERENCES receivers(id) ON DELETE CASCADE,
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    loan_amount DECIMAL(19, 2) NOT NULL,
    paid_amount DECIMAL(19, 2) NOT NULL DEFAULT 0.00,
    remaining_amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMP,
    last_payment_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Fix loans status constraint if needed
DO $$ 
BEGIN
    -- Drop existing constraint if it exists
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'loans_status_check') THEN
        ALTER TABLE loans DROP CONSTRAINT loans_status_check;
    END IF;
    
    -- Add new constraint with correct values
    ALTER TABLE loans 
    ADD CONSTRAINT loans_status_check 
    CHECK (status IN ('PENDING', 'PARTIALLY_PAID', 'COMPLETED'));
END $$;

-- Create indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_loans_user_id ON loans(user_id);
CREATE INDEX IF NOT EXISTS idx_loans_receiver_id ON loans(receiver_id);
CREATE INDEX IF NOT EXISTS idx_loans_transaction_id ON loans(transaction_id);
CREATE INDEX IF NOT EXISTS idx_loans_status ON loans(status);
CREATE INDEX IF NOT EXISTS idx_loans_user_receiver ON loans(user_id, receiver_id);

COMMENT ON TABLE loans IS 'Tracks loans given to users by merchants. Loans are created when a merchant does a LOAN top-up for a user.';
COMMENT ON COLUMN loans.loan_amount IS 'Total loan amount given to the user';
COMMENT ON COLUMN loans.paid_amount IS 'Amount paid back so far';
COMMENT ON COLUMN loans.remaining_amount IS 'Remaining amount to be paid back';
COMMENT ON COLUMN loans.status IS 'Loan status: PENDING, PARTIALLY_PAID, or COMPLETED';
COMMENT ON COLUMN loans.paid_at IS 'Timestamp when the loan was fully paid';
COMMENT ON COLUMN loans.last_payment_at IS 'Timestamp when the last payment was made';

-- ===================================================================
-- 8. Add is_flexible field to receivers table
-- ===================================================================
ALTER TABLE receivers
ADD COLUMN IF NOT EXISTS is_flexible BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN receivers.is_flexible IS 'If true, users can pay without checking receiver remaining balance. If false, both user balance and receiver balance are checked.';

-- Set all existing receivers to NON-FLEXIBLE mode (false)
UPDATE receivers
SET is_flexible = false
WHERE is_flexible IS NULL;

-- ===================================================================
-- 9. Add EFASHE category to payment_categories table
-- ===================================================================
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'EFASHE',
    'Default payment category: EFASHE',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'EFASHE'
);

-- ===================================================================
-- 9.1. Add GASOLINE, DIESEL, and QR Code categories to payment_categories table
-- ===================================================================
-- Add GASOLINE category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'GASOLINE',
    'Payment category for gasoline purchases',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'GASOLINE'
);

-- Add DIESEL category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'DIESEL',
    'Payment category for diesel purchases',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'DIESEL'
);

-- Add QR Code category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'QR Code',
    'Payment category for QR code payments',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'QR Code'
);

-- Add PAY_CUSTOMER category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'PAY_CUSTOMER',
    'Payment category for customer payments',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'PAY_CUSTOMER'
);

-- ===================================================================
-- 10. Create EFASHE Settings table
-- ===================================================================
CREATE TABLE IF NOT EXISTS efashe_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_type VARCHAR(20) NOT NULL UNIQUE,
    full_amount_phone_number VARCHAR(20) NOT NULL,
    cashback_phone_number VARCHAR(20) NOT NULL,
    agent_commission_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    customer_cashback_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    besoft_share_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create index on service_type for faster lookups
CREATE INDEX IF NOT EXISTS idx_efashe_settings_service_type ON efashe_settings(service_type);

-- Add comments
COMMENT ON TABLE efashe_settings IS 'EFASHE API service settings with payment distribution percentages';
COMMENT ON COLUMN efashe_settings.service_type IS 'Service type: AIRTIME, RRA, TV, MTN';
COMMENT ON COLUMN efashe_settings.full_amount_phone_number IS 'Phone number to receive full transaction amount (minus cashback)';
COMMENT ON COLUMN efashe_settings.cashback_phone_number IS 'Phone number to receive besoft share amount';
COMMENT ON COLUMN efashe_settings.agent_commission_percentage IS 'Agent commission percentage (0-100)';
COMMENT ON COLUMN efashe_settings.customer_cashback_percentage IS 'Customer cashback percentage (0-100)';
COMMENT ON COLUMN efashe_settings.besoft_share_percentage IS 'Besoft share percentage (0-100)';

-- ===================================================================
-- 11. Create EFASHE Transactions table
-- ===================================================================
CREATE TABLE IF NOT EXISTS efashe_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id VARCHAR(255) NOT NULL UNIQUE,
    service_type VARCHAR(20) NOT NULL,
    customer_phone VARCHAR(20) NOT NULL,
    customer_account_number VARCHAR(20) NOT NULL,
    amount NUMERIC(10, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'RWF',
    trx_id VARCHAR(255),
    mopay_transaction_id VARCHAR(255),
    mopay_status VARCHAR(50),
    efashe_status VARCHAR(50),
    delivery_method_id VARCHAR(50),
    deliver_to VARCHAR(500),
    poll_endpoint VARCHAR(500),
    retry_after_secs INTEGER,
    message VARCHAR(1000),
    error_message VARCHAR(1000),
    customer_cashback_amount NUMERIC(10, 2),
    besoft_share_amount NUMERIC(10, 2),
    full_amount_phone VARCHAR(20),
    cashback_phone VARCHAR(20),
    cashback_sent BOOLEAN DEFAULT false,
    full_amount_transaction_id VARCHAR(255),
    customer_cashback_transaction_id VARCHAR(255),
    besoft_share_transaction_id VARCHAR(255),
    initial_mopay_status VARCHAR(50),
    initial_efashe_status VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_transaction_id ON efashe_transactions(transaction_id);
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_service_type ON efashe_transactions(service_type);
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_customer_phone ON efashe_transactions(customer_phone);
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_efashe_status ON efashe_transactions(efashe_status);
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_mopay_status ON efashe_transactions(mopay_status);
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_created_at ON efashe_transactions(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_service_type_created_at ON efashe_transactions(service_type, created_at DESC);

-- Add comments
COMMENT ON TABLE efashe_transactions IS 'Stores EFASHE payment transactions history';
COMMENT ON COLUMN efashe_transactions.transaction_id IS 'EFASHE transaction ID (unique)';
COMMENT ON COLUMN efashe_transactions.service_type IS 'Service type: AIRTIME, RRA, TV, MTN';
COMMENT ON COLUMN efashe_transactions.customer_phone IS 'Customer phone number (12 digits with 250 prefix)';
COMMENT ON COLUMN efashe_transactions.customer_account_number IS 'Customer account number for EFASHE (format: 0XXXXXXXXX)';
COMMENT ON COLUMN efashe_transactions.trx_id IS 'EFASHE trxId from validate response';
COMMENT ON COLUMN efashe_transactions.mopay_transaction_id IS 'MoPay transaction ID';
COMMENT ON COLUMN efashe_transactions.efashe_status IS 'EFASHE transaction status: PENDING, SUCCESS, FAILED';
COMMENT ON COLUMN efashe_transactions.poll_endpoint IS 'EFASHE poll endpoint for async status checking';
COMMENT ON COLUMN efashe_transactions.customer_cashback_amount IS 'Amount to send to customer as cashback';
COMMENT ON COLUMN efashe_transactions.besoft_share_amount IS 'Amount to send to besoft phone';
COMMENT ON COLUMN efashe_transactions.full_amount_phone IS 'Phone number that receives full amount (minus cashback and besoft share)';
COMMENT ON COLUMN efashe_transactions.cashback_phone IS 'Phone number to receive besoft share';
COMMENT ON COLUMN efashe_transactions.cashback_sent IS 'Flag to track if cashback transfers were sent (now included in initial request)';
COMMENT ON COLUMN efashe_transactions.full_amount_transaction_id IS 'Unique transaction ID for full amount transfer to full amount phone';
COMMENT ON COLUMN efashe_transactions.customer_cashback_transaction_id IS 'Unique transaction ID for customer cashback transfer';
COMMENT ON COLUMN efashe_transactions.besoft_share_transaction_id IS 'Unique transaction ID for besoft share transfer';
COMMENT ON COLUMN efashe_transactions.initial_mopay_status IS 'Initial MoPay status when transaction was first created';
COMMENT ON COLUMN efashe_transactions.initial_efashe_status IS 'Initial EFASHE status when transaction was first created';

-- Add indexes for transfer transaction IDs (if not already created)
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_full_amount_tx_id ON efashe_transactions(full_amount_transaction_id);
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_customer_cashback_tx_id ON efashe_transactions(customer_cashback_transaction_id);
CREATE INDEX IF NOT EXISTS idx_efashe_transactions_besoft_share_tx_id ON efashe_transactions(besoft_share_transaction_id);

-- Ensure initial status columns exist (for existing databases)
ALTER TABLE efashe_transactions 
ADD COLUMN IF NOT EXISTS initial_mopay_status VARCHAR(50),
ADD COLUMN IF NOT EXISTS initial_efashe_status VARCHAR(50);

-- Add customer_account_name column for storing customer account name (e.g., "MUHINZI ANDRE" for electricity, TIN owner for RRA)
ALTER TABLE efashe_transactions 
ADD COLUMN IF NOT EXISTS customer_account_name VARCHAR(255);

COMMENT ON COLUMN efashe_transactions.customer_account_name IS 'Customer account name (e.g., "MUHINZI ANDRE" for electricity, TIN owner for RRA)';

-- Add validated, payment_mode, and callback_url columns for two-step payment processing
ALTER TABLE efashe_transactions 
ADD COLUMN IF NOT EXISTS validated VARCHAR(20),
ADD COLUMN IF NOT EXISTS payment_mode VARCHAR(50),
ADD COLUMN IF NOT EXISTS callback_url VARCHAR(500);

COMMENT ON COLUMN efashe_transactions.validated IS 'Validation status: INITIAL (validated but not processed), PROCESS (ready to process MoPay)';
COMMENT ON COLUMN efashe_transactions.payment_mode IS 'Payment mode for MoPay processing (e.g., MOBILE)';
COMMENT ON COLUMN efashe_transactions.callback_url IS 'Callback URL for MoPay processing';

-- Add token column for electricity purchases (if available)
ALTER TABLE efashe_transactions
ADD COLUMN IF NOT EXISTS token VARCHAR(255);

COMMENT ON COLUMN efashe_transactions.token IS 'Electricity token returned by provider';

-- Make amount nullable for RRA service type (amount is optional for RRA)
ALTER TABLE efashe_transactions 
ALTER COLUMN amount DROP NOT NULL;

COMMENT ON COLUMN efashe_transactions.amount IS 'Transaction amount (nullable for RRA service type, required for all other services)';

COMMIT;

-- ===================================================================
-- 12. Create USSD user settings table
-- ===================================================================
CREATE TABLE IF NOT EXISTS ussd_user_settings (
    phone_number VARCHAR(20) PRIMARY KEY,
    locale VARCHAR(5) NOT NULL DEFAULT 'en',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ussd_user_settings IS 'Stores USSD user preferences such as language';
COMMENT ON COLUMN ussd_user_settings.phone_number IS 'MSISDN used for USSD sessions';
COMMENT ON COLUMN ussd_user_settings.locale IS 'Preferred language (en or rw)';

-- ===================================================================
-- Migration Complete!
-- ===================================================================
-- Verify the changes by running:
--   \d receivers
--   \d transactions  
--   \d balance_assignment_history
--   \d merchant_user_balances
--   \d loans
--   \d efashe_settings
--   \d efashe_transactions
-- ===================================================================

