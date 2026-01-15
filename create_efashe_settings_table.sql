-- Create EFASHE Settings table
CREATE TABLE IF NOT EXISTS efashe_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_type VARCHAR(20) NOT NULL UNIQUE,
    full_amount_phone_number VARCHAR(20) NOT NULL,
    cashback_phone_number VARCHAR(20) NOT NULL,
    agent_commission_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    customer_cashback_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    besoft_share_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create index on service_type for faster lookups
CREATE INDEX IF NOT EXISTS idx_efashe_settings_service_type ON efashe_settings(service_type);

-- Add comment to table
COMMENT ON TABLE efashe_settings IS 'EFASHE API service settings with payment distribution percentages';
COMMENT ON COLUMN efashe_settings.service_type IS 'Service type: AIRTIME, RRA, TV, MTN';
COMMENT ON COLUMN efashe_settings.full_amount_phone_number IS 'Phone number to receive full transaction amount (minus cashback)';
COMMENT ON COLUMN efashe_settings.cashback_phone_number IS 'Phone number to receive customer cashback amount';
COMMENT ON COLUMN efashe_settings.agent_commission_percentage IS 'Agent commission percentage (0-100)';
COMMENT ON COLUMN efashe_settings.customer_cashback_percentage IS 'Customer cashback percentage (0-100)';
COMMENT ON COLUMN efashe_settings.besoft_share_percentage IS 'Besoft share percentage (0-100)';

