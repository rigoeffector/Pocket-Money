-- Add EFASHE category to payment_categories table
-- This will only insert if the category doesn't already exist

INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'EFASHE',
    'Default payment category: EFASHE',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'EFASHE'
);

