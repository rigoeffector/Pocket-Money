-- Migration: Fix negative remaining balance values
-- This script sets all negative remaining balance values to 0 for both users and receivers
-- Can be run both locally and remotely

-- Fix negative remaining_balance in receivers table
UPDATE receivers
SET remaining_balance = 0
WHERE remaining_balance < 0;

-- Fix negative amount_remaining in users table
UPDATE users
SET amount_remaining = 0
WHERE amount_remaining < 0;

-- Verify the update - show counts of records that were fixed
SELECT 
    'receivers' as table_name,
    COUNT(*) as records_with_negative_remaining_balance
FROM receivers
WHERE remaining_balance < 0
UNION ALL
SELECT 
    'users' as table_name,
    COUNT(*) as records_with_negative_amount_remaining
FROM users
WHERE amount_remaining < 0;

-- Show summary of remaining balance ranges
SELECT 
    'receivers' as table_name,
    COUNT(*) FILTER (WHERE remaining_balance = 0) as zero_balance,
    COUNT(*) FILTER (WHERE remaining_balance > 0) as positive_balance,
    COUNT(*) FILTER (WHERE remaining_balance < 0) as negative_balance,
    MIN(remaining_balance) as min_balance,
    MAX(remaining_balance) as max_balance
FROM receivers
UNION ALL
SELECT 
    'users' as table_name,
    COUNT(*) FILTER (WHERE amount_remaining = 0) as zero_balance,
    COUNT(*) FILTER (WHERE amount_remaining > 0) as positive_balance,
    COUNT(*) FILTER (WHERE amount_remaining < 0) as negative_balance,
    MIN(amount_remaining) as min_balance,
    MAX(amount_remaining) as max_balance
FROM users;

