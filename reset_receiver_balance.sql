-- Reset assigned balance for specific receiver
-- Receiver ID: 09fe25d6-6935-4ba1-b345-7a06995f350c

UPDATE receivers
SET assigned_balance = 0
WHERE id = '09fe25d6-6935-4ba1-b345-7a06995f350c';

-- Optionally, you can also reset remaining_balance to 0 if needed
-- Uncomment the following line if you want to reset remaining_balance as well:
-- UPDATE receivers SET remaining_balance = 0 WHERE id = '09fe25d6-6935-4ba1-b345-7a06995f350c';

-- Verify the update
SELECT id, company_name, assigned_balance, remaining_balance 
FROM receivers 
WHERE id = '09fe25d6-6935-4ba1-b345-7a06995f350c';

