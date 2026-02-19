-- Migration: Add indexes for optimized search performance on efashe_transactions
-- Date: 2026-01-XX
-- Description: Add indexes on all searchable columns to improve search query performance

-- Indexes for exact match searches (most efficient)
-- Note: transaction_id already has a UNIQUE constraint which creates an index automatically
-- But we add explicit indexes for other transaction ID fields for consistency
CREATE INDEX IF NOT EXISTS idx_efashe_transaction_id ON efashe_transactions(transaction_id);
CREATE INDEX IF NOT EXISTS idx_efashe_mopay_transaction_id ON efashe_transactions(mopay_transaction_id);
CREATE INDEX IF NOT EXISTS idx_efashe_trx_id ON efashe_transactions(trx_id);
CREATE INDEX IF NOT EXISTS idx_efashe_customer_account_number ON efashe_transactions(customer_account_number);
CREATE INDEX IF NOT EXISTS idx_efashe_token ON efashe_transactions(token);
CREATE INDEX IF NOT EXISTS idx_efashe_amount ON efashe_transactions(amount);

-- Case-insensitive indexes for text search (PostgreSQL supports this)
-- These indexes use LOWER() function which allows case-insensitive searches to use indexes
CREATE INDEX IF NOT EXISTS idx_efashe_customer_phone_lower ON efashe_transactions(LOWER(customer_phone));
CREATE INDEX IF NOT EXISTS idx_efashe_customer_account_name_lower ON efashe_transactions(LOWER(customer_account_name));
CREATE INDEX IF NOT EXISTS idx_efashe_transaction_id_lower ON efashe_transactions(LOWER(transaction_id));
CREATE INDEX IF NOT EXISTS idx_efashe_mopay_status_lower ON efashe_transactions(LOWER(mopay_status));
CREATE INDEX IF NOT EXISTS idx_efashe_efashe_status_lower ON efashe_transactions(LOWER(efashe_status));

-- Composite indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_efashe_phone_created_at ON efashe_transactions(customer_phone, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_efashe_service_type_status ON efashe_transactions(service_type, mopay_status, created_at DESC);

-- Full-text search index for account names (if using PostgreSQL full-text search)
-- This is optional but can significantly improve text search performance
-- CREATE INDEX IF NOT EXISTS idx_efashe_account_name_fts ON efashe_transactions USING gin(to_tsvector('english', COALESCE(customer_account_name, '')));

-- Comments
COMMENT ON INDEX idx_efashe_transaction_id IS 'Index for searching by EFASHE transaction ID (primary transaction identifier)';
COMMENT ON INDEX idx_efashe_mopay_transaction_id IS 'Index for searching by MoPay transaction ID';
COMMENT ON INDEX idx_efashe_trx_id IS 'Index for searching by EFASHE trxId';
COMMENT ON INDEX idx_efashe_customer_account_number IS 'Index for searching by customer account number';
COMMENT ON INDEX idx_efashe_token IS 'Index for searching by electricity token';
COMMENT ON INDEX idx_efashe_amount IS 'Index for searching by amount';
COMMENT ON INDEX idx_efashe_customer_phone_lower IS 'Case-insensitive index for customer phone search';
COMMENT ON INDEX idx_efashe_customer_account_name_lower IS 'Case-insensitive index for account name search';
COMMENT ON INDEX idx_efashe_transaction_id_lower IS 'Case-insensitive index for transaction ID search';
COMMENT ON INDEX idx_efashe_mopay_status_lower IS 'Case-insensitive index for MoPay status search';
COMMENT ON INDEX idx_efashe_efashe_status_lower IS 'Case-insensitive index for EFASHE status search';
