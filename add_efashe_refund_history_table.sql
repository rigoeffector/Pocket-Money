-- Migration script to create efashe_refund_history table
-- Run this script on your PostgreSQL database

CREATE TABLE IF NOT EXISTS efashe_refund_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    efashe_transaction_id UUID NOT NULL REFERENCES efashe_transactions(id) ON DELETE CASCADE,
    original_transaction_id VARCHAR(255) NOT NULL, -- EFASHE transaction ID
    refund_transaction_id VARCHAR(255) NOT NULL UNIQUE, -- MoPay refund transaction ID
    refund_amount NUMERIC(10, 2) NOT NULL,
    admin_phone VARCHAR(20) NOT NULL,
    receiver_phone VARCHAR(20) NOT NULL,
    message VARCHAR(1000),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING', -- PENDING, SUCCESS, FAILED
    mopay_status VARCHAR(50), -- MoPay status from check-status endpoint
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for faster queries
CREATE INDEX IF NOT EXISTS idx_efashe_refund_history_efashe_transaction_id ON efashe_refund_history(efashe_transaction_id);
CREATE INDEX IF NOT EXISTS idx_efashe_refund_history_original_transaction_id ON efashe_refund_history(original_transaction_id);
CREATE INDEX IF NOT EXISTS idx_efashe_refund_history_refund_transaction_id ON efashe_refund_history(refund_transaction_id);
CREATE INDEX IF NOT EXISTS idx_efashe_refund_history_status ON efashe_refund_history(status);
CREATE INDEX IF NOT EXISTS idx_efashe_refund_history_receiver_phone ON efashe_refund_history(receiver_phone);
CREATE INDEX IF NOT EXISTS idx_efashe_refund_history_created_at ON efashe_refund_history(created_at DESC);

COMMENT ON TABLE efashe_refund_history IS 'Tracks history of refunds for EFASHE transactions';
COMMENT ON COLUMN efashe_refund_history.efashe_transaction_id IS 'Reference to the original EFASHE transaction';
COMMENT ON COLUMN efashe_refund_history.original_transaction_id IS 'Original EFASHE transaction ID';
COMMENT ON COLUMN efashe_refund_history.refund_transaction_id IS 'MoPay transaction ID for the refund';
COMMENT ON COLUMN efashe_refund_history.refund_amount IS 'Amount refunded (transaction amount minus cashback)';
COMMENT ON COLUMN efashe_refund_history.admin_phone IS 'Admin phone number (DEBIT - who pays for refund)';
COMMENT ON COLUMN efashe_refund_history.receiver_phone IS 'Receiver phone number (CREDIT - who receives refund)';
COMMENT ON COLUMN efashe_refund_history.status IS 'Refund status: PENDING, SUCCESS, FAILED';
COMMENT ON COLUMN efashe_refund_history.mopay_status IS 'MoPay status from check-status endpoint';
