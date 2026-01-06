-- Database Cleanup Script
-- This script deletes all data EXCEPT users, receivers, and payment_categories tables
-- WARNING: This is a destructive operation! Make sure you have backups.

-- Start transaction
BEGIN;

-- 1. Delete all Loans FIRST (they reference transactions)
DELETE FROM loans;
SELECT 'Deleted all loans' AS status;

-- 2. Delete all Transactions (after loans are deleted)
-- This deletes ALL transaction records from the transactions table
DELETE FROM transactions;
SELECT 'Deleted all transactions' AS status;

-- 3. Delete all Merchant User Balances
DELETE FROM merchant_user_balances;
SELECT 'Deleted all merchant user balances' AS status;

-- 4. Delete all Payment Commission Settings
DELETE FROM payment_commission_settings;
SELECT 'Deleted all payment commission settings' AS status;

-- 5. Delete all Balance Assignment History
DELETE FROM balance_assignment_history;
SELECT 'Deleted all balance assignment history' AS status;

-- 6. Delete all Global Settings
DELETE FROM global_settings;
SELECT 'Deleted all global settings' AS status;

-- 7. Payment Categories table is kept (do nothing)

-- 8. Delete all Services
DELETE FROM services;
SELECT 'Deleted all services' AS status;

-- 9. Delete all Auth records EXCEPT ADMIN role
DELETE FROM auth WHERE role != 'ADMIN';
SELECT 'Deleted all non-admin auth records' AS status;

-- 10. Reset all User balances to 0.0
UPDATE users SET 
    amount_on_card = 0.00,
    amount_remaining = 0.00,
    last_transaction_date = NULL;
SELECT 'Reset all user balances to 0.0' AS status;

-- 11. Reset all Receiver balances and percentages to 0.0
UPDATE receivers SET 
    wallet_balance = 0.00,
    total_received = 0.00,
    assigned_balance = 0.00,
    remaining_balance = 0.00,
    discount_percentage = 0.00,
    user_bonus_percentage = 0.00,
    last_transaction_date = NULL;
SELECT 'Reset all receiver balances, percentages, and last_transaction_date' AS status;

-- 12. Users table is kept (with balances reset)
-- 13. Receivers table is kept (with balances reset)
-- 14. Payment Categories table is kept (do nothing)
-- 15. ADMIN users in auth table are kept (do nothing)

-- Show summary
SELECT 
    (SELECT COUNT(*) FROM users) AS users_kept,
    (SELECT COUNT(*) FROM receivers) AS receivers_kept,
    (SELECT COUNT(*) FROM auth WHERE role = 'ADMIN') AS admin_users_kept,
    (SELECT SUM(amount_on_card) FROM users) AS total_user_amount_on_card,
    (SELECT SUM(amount_remaining) FROM users) AS total_user_amount_remaining,
    (SELECT SUM(wallet_balance) FROM receivers) AS total_receiver_wallet_balance,
    (SELECT SUM(assigned_balance) FROM receivers) AS total_receiver_assigned_balance,
    (SELECT COUNT(*) FROM transactions) AS transactions_remaining,
    (SELECT COUNT(*) FROM loans) AS loans_remaining,
    (SELECT COUNT(*) FROM merchant_user_balances) AS merchant_balances_remaining;

-- Commit transaction
COMMIT;

-- Verification queries (run after commit to verify)
-- SELECT 'Verification:' AS info;
-- SELECT COUNT(*) AS payment_categories_count FROM payment_categories;
-- SELECT COUNT(*) AS admin_users_count FROM auth WHERE role = 'ADMIN';
-- SELECT COUNT(*) AS total_auth_count FROM auth;
-- SELECT COUNT(*) AS transactions_count FROM transactions;
-- SELECT COUNT(*) AS users_count FROM users;
-- SELECT COUNT(*) AS receivers_count FROM receivers;

