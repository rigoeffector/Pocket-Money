-- Migration script to add loans table for tracking loan transactions
-- This table tracks loans given to users by merchants

BEGIN;

-- Create loans table
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
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT loans_status_check CHECK (status IN ('PENDING', 'PARTIALLY_PAID', 'COMPLETED'))
);

-- Create indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_loans_user_id ON loans(user_id);
CREATE INDEX IF NOT EXISTS idx_loans_receiver_id ON loans(receiver_id);
CREATE INDEX IF NOT EXISTS idx_loans_transaction_id ON loans(transaction_id);
CREATE INDEX IF NOT EXISTS idx_loans_status ON loans(status);
CREATE INDEX IF NOT EXISTS idx_loans_user_receiver ON loans(user_id, receiver_id);

-- Add comments
COMMENT ON TABLE loans IS 'Tracks loans given to users by merchants. Loans are created when a merchant does a LOAN top-up for a user.';
COMMENT ON COLUMN loans.loan_amount IS 'Total loan amount given to the user';
COMMENT ON COLUMN loans.paid_amount IS 'Amount paid back so far';
COMMENT ON COLUMN loans.remaining_amount IS 'Remaining amount to be paid back';
COMMENT ON COLUMN loans.status IS 'Loan status: PENDING, PARTIALLY_PAID, or COMPLETED';
COMMENT ON COLUMN loans.paid_at IS 'Timestamp when the loan was fully paid';
COMMENT ON COLUMN loans.last_payment_at IS 'Timestamp when the last payment was made';

COMMIT;

