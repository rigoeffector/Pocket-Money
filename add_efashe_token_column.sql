-- Migration: Add token column to efashe_transactions table
-- This column stores the electricity token number from /electricity/tokens endpoint
-- Date: 2026-01-18

-- Add token column to efashe_transactions table
ALTER TABLE efashe_transactions ADD COLUMN IF NOT EXISTS token VARCHAR(100);

-- Add comment to column
COMMENT ON COLUMN efashe_transactions.token IS 'Electricity token number from /electricity/tokens endpoint (formatted with dashes)';
