-- Add GASOLINE and DIESEL categories to payment_categories table
-- This will only insert if the categories don't already exist

-- Add GASOLINE category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'GASOLINE',
    'Payment category for gasoline purchases',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'GASOLINE'
);

-- Add DIESEL category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'DIESEL',
    'Payment category for diesel purchases',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'DIESEL'
);

-- Add QR Code category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'QR Code',
    'Payment category for QR code payments',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'QR Code'
);

-- Add PAY_CUSTOMER category
INSERT INTO payment_categories (id, name, description, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'PAY_CUSTOMER',
    'Payment category for customer payments',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM payment_categories WHERE name = 'PAY_CUSTOMER'
);
