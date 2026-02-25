-- Create RRA Range Settings table
-- This table stores range-based percentage settings for RRA payments
-- Admin can configure multiple ranges with different percentages
-- Example: 0-100k: 0%, 100k-200k: 2%, 200k+: 5%

CREATE TABLE IF NOT EXISTS rra_range_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    min_amount NUMERIC(15,2) NOT NULL,
    max_amount NUMERIC(15,2), -- NULL means no upper limit
    percentage NUMERIC(5,2) NOT NULL, -- Percentage (0-100) to apply for amounts in this range
    is_active BOOLEAN NOT NULL DEFAULT true,
    priority INTEGER NOT NULL, -- Lower number = higher priority (checked first)
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create index on priority for faster lookups
CREATE INDEX IF NOT EXISTS idx_rra_range_settings_priority ON rra_range_settings(priority);

-- Create index on min_amount and max_amount for range queries
CREATE INDEX IF NOT EXISTS idx_rra_range_settings_amounts ON rra_range_settings(min_amount, max_amount);

-- Create index on is_active for filtering active ranges
CREATE INDEX IF NOT EXISTS idx_rra_range_settings_active ON rra_range_settings(is_active);

-- Add comments
COMMENT ON TABLE rra_range_settings IS 'Range-based percentage settings for RRA payments. Admin can configure multiple ranges with different percentages.';
COMMENT ON COLUMN rra_range_settings.min_amount IS 'Minimum amount for this range (inclusive)';
COMMENT ON COLUMN rra_range_settings.max_amount IS 'Maximum amount for this range (exclusive, NULL means no upper limit)';
COMMENT ON COLUMN rra_range_settings.percentage IS 'Percentage (0-100) to apply for amounts in this range';
COMMENT ON COLUMN rra_range_settings.is_active IS 'Whether this range setting is active';
COMMENT ON COLUMN rra_range_settings.priority IS 'Lower number = higher priority (checked first when multiple ranges match)';
