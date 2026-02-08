-- Add USSD user settings table for language preferences
CREATE TABLE IF NOT EXISTS ussd_user_settings (
    phone_number VARCHAR(20) PRIMARY KEY,
    locale VARCHAR(5) NOT NULL DEFAULT 'en',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ussd_user_settings IS 'Stores USSD user preferences such as language';
COMMENT ON COLUMN ussd_user_settings.phone_number IS 'MSISDN used for USSD sessions';
COMMENT ON COLUMN ussd_user_settings.locale IS 'Preferred language (en or rw)';
