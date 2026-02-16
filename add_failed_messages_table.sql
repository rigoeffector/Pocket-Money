-- Migration script to add failed_messages table
-- This table stores failed SMS and WhatsApp messages for retry purposes

CREATE TABLE IF NOT EXISTS failed_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_type VARCHAR(20) NOT NULL,
    phone_number VARCHAR(50) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    error_message VARCHAR(1000),
    retry_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    last_retry_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Add comments
COMMENT ON TABLE failed_messages IS 'Stores failed SMS and WhatsApp messages for retry purposes';
COMMENT ON COLUMN failed_messages.message_type IS 'Type of message: SMS or WHATSAPP';
COMMENT ON COLUMN failed_messages.phone_number IS 'Recipient phone number';
COMMENT ON COLUMN failed_messages.message IS 'The message content that failed to send';
COMMENT ON COLUMN failed_messages.error_message IS 'Error message from the failed send attempt';
COMMENT ON COLUMN failed_messages.retry_count IS 'Number of times this message has been retried';
COMMENT ON COLUMN failed_messages.status IS 'Status: PENDING, RESENT, RESENT_SUCCESS, RESENT_FAILED';
COMMENT ON COLUMN failed_messages.last_retry_at IS 'Timestamp of the last retry attempt';

-- Create indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_failed_messages_status ON failed_messages(status);
CREATE INDEX IF NOT EXISTS idx_failed_messages_message_type ON failed_messages(message_type);
CREATE INDEX IF NOT EXISTS idx_failed_messages_message_type_status ON failed_messages(message_type, status);
CREATE INDEX IF NOT EXISTS idx_failed_messages_created_at ON failed_messages(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_failed_messages_phone_number ON failed_messages(phone_number);
