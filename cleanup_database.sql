-- Database Cleanup Script
-- This script deletes all data EXCEPT PaymentCategories and ADMIN users
-- WARNING: This is a destructive operation! Make sure you have backups.

-- Start transaction
BEGIN;

-- 1. Delete all Transactions
DELETE FROM transactions;
SELECT 'Deleted all transactions' AS status;

-- 2. Delete all Payment Commission Settings
DELETE FROM payment_commission_settings;
SELECT 'Deleted all payment commission settings' AS status;

-- 3. Delete all Balance Assignment History
DELETE FROM balance_assignment_history;
SELECT 'Deleted all balance assignment history' AS status;

-- 4. Delete all Users
DELETE FROM users;
SELECT 'Deleted all users' AS status;

-- 5. Delete all Receivers
DELETE FROM receivers;
SELECT 'Deleted all receivers' AS status;

-- 6. Delete all Global Settings
DELETE FROM global_settings;
SELECT 'Deleted all global settings' AS status;

-- 7. Delete all Auth records EXCEPT ADMIN role
DELETE FROM auth WHERE role != 'ADMIN';
SELECT 'Deleted all non-admin auth records' AS status;

-- 8. PaymentCategories are kept (do nothing)
-- 9. ADMIN users in auth table are kept (do nothing)

-- Show summary
SELECT 
    (SELECT COUNT(*) FROM payment_categories) AS categories_kept,
    (SELECT COUNT(*) FROM auth WHERE role = 'ADMIN') AS admin_users_kept,
    (SELECT COUNT(*) FROM transactions) AS transactions_remaining,
    (SELECT COUNT(*) FROM users) AS users_remaining,
    (SELECT COUNT(*) FROM receivers) AS receivers_remaining;

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

