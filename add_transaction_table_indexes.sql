-- Migration: Add indexes for optimized transaction loading performance
-- Date: 2026-01-XX
-- Description: Add indexes on Transaction table to improve query performance

-- Indexes for exact match searches (most efficient)
CREATE INDEX IF NOT EXISTS idx_transactions_mopay_transaction_id ON transactions(mopay_transaction_id);
CREATE INDEX IF NOT EXISTS idx_transactions_user_id ON transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_receiver_id ON transactions(receiver_id);
CREATE INDEX IF NOT EXISTS idx_transactions_payment_category_id ON transactions(payment_category_id);
CREATE INDEX IF NOT EXISTS idx_transactions_status ON transactions(status);
CREATE INDEX IF NOT EXISTS idx_transactions_transaction_type ON transactions(transaction_type);

-- Index for date-based queries (most common)
CREATE INDEX IF NOT EXISTS idx_transactions_created_at_desc ON transactions(created_at DESC);

-- Case-insensitive indexes for text search
CREATE INDEX IF NOT EXISTS idx_transactions_phone_number_lower ON transactions(LOWER(phone_number));
CREATE INDEX IF NOT EXISTS idx_transactions_mopay_tx_id_lower ON transactions(LOWER(mopay_transaction_id));

-- Composite indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_transactions_receiver_created_at ON transactions(receiver_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_user_created_at ON transactions(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_type_status_created_at ON transactions(transaction_type, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_receiver_type_created_at ON transactions(receiver_id, transaction_type, created_at DESC);

-- Comments
COMMENT ON INDEX idx_transactions_mopay_transaction_id IS 'Index for searching by MoPay transaction ID';
COMMENT ON INDEX idx_transactions_user_id IS 'Index for filtering by user';
COMMENT ON INDEX idx_transactions_receiver_id IS 'Index for filtering by receiver/merchant';
COMMENT ON INDEX idx_transactions_status IS 'Index for filtering by transaction status';
COMMENT ON INDEX idx_transactions_transaction_type IS 'Index for filtering by transaction type';
COMMENT ON INDEX idx_transactions_created_at_desc IS 'Index for date-based queries (most common)';
COMMENT ON INDEX idx_transactions_receiver_created_at IS 'Composite index for receiver transactions ordered by date';
COMMENT ON INDEX idx_transactions_user_created_at IS 'Composite index for user transactions ordered by date';
COMMENT ON INDEX idx_transactions_type_status_created_at IS 'Composite index for transaction type and status queries';
COMMENT ON INDEX idx_transactions_receiver_type_created_at IS 'Composite index for receiver and type queries';
