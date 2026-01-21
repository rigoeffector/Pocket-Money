-- Set transaction CHQ1769018477 to FAILED on EFASHE status (local database)
-- This is for testing purposes

-- First, check current status
SELECT 
    transaction_id,
    mopay_transaction_id,
    mopay_status,
    efashe_status,
    amount,
    customer_phone,
    full_amount_phone,
    error_message,
    updated_at
FROM efashe_transactions 
WHERE transaction_id = 'CDE1768743882' OR mopay_transaction_id = 'CDE1768743882';

-- Update transaction to set EFASHE status to FAILED
UPDATE efashe_transactions 
SET 
    efashe_status = 'FAILED',
    error_message = COALESCE(error_message, '') || ' | Test: EFASHE transaction set to FAILED for testing',
    updated_at = NOW()
WHERE transaction_id = 'CHQ1769018477' OR mopay_transaction_id = 'CHQ1769018477';

-- Verify the update
SELECT 
    transaction_id,
    mopay_transaction_id,
    mopay_status,
    efashe_status,
    amount,
    customer_phone,
    full_amount_phone,
    error_message,
    updated_at
FROM efashe_transactions 
WHERE transaction_id = 'CHQ1769018477' OR mopay_transaction_id = 'CHQ1769018477';
