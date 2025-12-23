-- Migration script to add status and approval fields to balance_assignment_history table
-- Run this script on your PostgreSQL database

ALTER TABLE balance_assignment_history
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS approved_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP;

-- Create index for faster queries by status
CREATE INDEX IF NOT EXISTS idx_balance_assignment_history_status ON balance_assignment_history(status);
CREATE INDEX IF NOT EXISTS idx_balance_assignment_history_receiver_status ON balance_assignment_history(receiver_id, status);

COMMENT ON COLUMN balance_assignment_history.status IS 'Status of balance assignment: PENDING, APPROVED, REJECTED';
COMMENT ON COLUMN balance_assignment_history.approved_by IS 'Username or identifier of who approved/rejected the balance assignment';
COMMENT ON COLUMN balance_assignment_history.approved_at IS 'Timestamp when the balance assignment was approved or rejected';

