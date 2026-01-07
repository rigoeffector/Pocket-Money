-- Migration: Reset all submerchant balances to 0
-- This script resets all balance fields for all submerchants (receivers with parent_receiver_id)
-- Can be run both locally and remotely

-- Reset all balance fields for all submerchants
UPDATE receivers
SET 
    wallet_balance = 0,
    total_received = 0,
    assigned_balance = 0,
    remaining_balance = 0
WHERE parent_receiver_id IS NOT NULL;

-- Verify the update - show all submerchants and their balances
SELECT 
    id,
    company_name,
    parent_receiver_id,
    wallet_balance,
    total_received,
    assigned_balance,
    remaining_balance
FROM receivers
WHERE parent_receiver_id IS NOT NULL
ORDER BY company_name;

-- Count how many submerchants were reset
SELECT COUNT(*) as submerchants_reset
FROM receivers
WHERE parent_receiver_id IS NOT NULL;

