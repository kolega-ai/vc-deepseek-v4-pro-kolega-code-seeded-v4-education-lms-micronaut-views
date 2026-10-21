-- The decision principal carries the channel's user id so authorization
-- responses can reference the same identity the channel's business data uses.
ALTER TABLE principal ADD COLUMN IF NOT EXISTS external_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_principal_external ON principal(external_id) WHERE external_id IS NOT NULL;
