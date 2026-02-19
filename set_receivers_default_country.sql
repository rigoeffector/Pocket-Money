-- Migration script to set default country and country code for all receivers
-- Sets all receivers to Rwanda country and +250 country code if they are NULL

BEGIN;

-- Update all receivers with NULL country to "Rwanda"
UPDATE receivers
SET country = 'Rwanda'
WHERE country IS NULL;

-- Update all receivers with NULL country_code to "+250"
UPDATE receivers
SET country_code = '+250'
WHERE country_code IS NULL;

-- Verify the updates
SELECT 
    COUNT(*) as total_receivers,
    COUNT(CASE WHEN country = 'Rwanda' THEN 1 END) as rwanda_count,
    COUNT(CASE WHEN country_code = '+250' THEN 1 END) as country_code_count
FROM receivers;

COMMIT;
