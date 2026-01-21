-- Test script to update transaction CHQ1769018477 for refund testing
-- Sets EFASHE status to FAILED while keeping MoPay status as SUCCESS (200)
-- This will trigger the refund logic

-- First, check current status
SELECT 
    transaction_id,
    mopay_status,
    efashe_status,
    amount,
    customer_phone,
    full_amount_phone,
    error_message
FROM efashe_transactions 
WHERE transaction_id = 'CDE1768743882' OR mopay_transaction_id = 'CDE1768743882';

-- Update transaction to test refund: EFASHE FAILED + MoPay SUCCESS
UPDATE efashe_transactions 
SET 
    efashe_status = 'FAILED',
    mopay_status = '200',
    error_message = 'Test: EFASHE transaction failed for refund testing',
    updated_at = NOW()
WHERE transaction_id = 'CDE1768743882' OR mopay_transaction_id = 'CDE1768743882';

-- Verify the update
SELECT 
    transaction_id,
    mopay_status,
    efashe_status,
    amount,
    customer_phone,
    full_amount_phone,
    error_message,
    updated_at
FROM efashe_transactions 
WHERE transaction_id = 'CHQ1769018477' OR mopay_transaction_id = 'CHQ1769018477';
