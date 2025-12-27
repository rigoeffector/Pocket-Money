-- Add parent_receiver_id column to receivers table for submerchant relationships
ALTER TABLE receivers ADD COLUMN IF NOT EXISTS parent_receiver_id UUID;

-- Add foreign key constraint
ALTER TABLE receivers 
ADD CONSTRAINT fk_receivers_parent_receiver 
FOREIGN KEY (parent_receiver_id) 
REFERENCES receivers(id) 
ON DELETE SET NULL;

-- Add index for better query performance
CREATE INDEX IF NOT EXISTS idx_receivers_parent_receiver_id ON receivers(parent_receiver_id);

-- Add comment
COMMENT ON COLUMN receivers.parent_receiver_id IS 'Reference to parent receiver for submerchant relationships. NULL if main merchant.';

