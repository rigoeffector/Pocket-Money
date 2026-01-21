-- Reset transaction CHQ1769018477 for testing (local database)
-- Removes REFUND_PROCESSED flag and cleans up duplicate error messages

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
WHERE transaction_id = 'CHQ1769018477' OR mopay_transaction_id = 'CHQ1769018477';

-- Update transaction to reset for testing
UPDATE efashe_transactions 
SET 
    efashe_status = 'FAILED',
    mopay_status = '200',
    error_message = 'Test: EFASHE transaction set to FAILED for refund testing',
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
