-- Migration script to create balance_assignment_history table
-- Run this script on your PostgreSQL database

CREATE TABLE IF NOT EXISTS balance_assignment_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receiver_id UUID NOT NULL REFERENCES receivers(id) ON DELETE CASCADE,
    assigned_balance NUMERIC(19, 2) NOT NULL,
    previous_assigned_balance NUMERIC(19, 2),
    balance_difference NUMERIC(19, 2),
    assigned_by VARCHAR(255),
    notes VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create index for faster queries
CREATE INDEX IF NOT EXISTS idx_balance_assignment_history_receiver_id ON balance_assignment_history(receiver_id);
CREATE INDEX IF NOT EXISTS idx_balance_assignment_history_created_at ON balance_assignment_history(created_at DESC);

COMMENT ON TABLE balance_assignment_history IS 'Tracks history of balance assignments to receivers';
COMMENT ON COLUMN balance_assignment_history.receiver_id IS 'Reference to receiver who received the balance';
COMMENT ON COLUMN balance_assignment_history.assigned_balance IS 'New assigned balance amount';
COMMENT ON COLUMN balance_assignment_history.previous_assigned_balance IS 'Previous assigned balance before this assignment';
COMMENT ON COLUMN balance_assignment_history.balance_difference IS 'Difference between new and previous balance';
COMMENT ON COLUMN balance_assignment_history.assigned_by IS 'Username or identifier of who assigned the balance';
COMMENT ON COLUMN balance_assignment_history.notes IS 'Optional notes about the balance assignment';

