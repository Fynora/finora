-- When a soft-deleted statement_imports row stopped being a reason for the storage sweep to look at
-- its object.
--
-- StatementStorageSweepService discovers candidates from soft-deleted rows whose deleted_at is past
-- the retention window. Those rows are never removed, so once the sweep had deleted an object the
-- same rows returned the same key on every later run, and the idempotent storage delete
-- "succeeded" again each time. Discovery is oldest first under a batch limit, so once a batch's
-- worth of keys had been dealt with, every run took those same keys and never reached a newer one.
--
-- Set on every soft-deleted row naming the key once the sweep has deleted the object, or has found
-- a live statement_imports row still naming it -- that row's own deletion makes the key a
-- candidate again. Not set when the delete failed, or when only a session or an import job still
-- names the key: neither leaves a soft-deleted row behind, so these rows stay the only way back to
-- the object.
ALTER TABLE statement_imports ADD COLUMN object_released_at TIMESTAMPTZ;

COMMENT ON COLUMN statement_imports.object_released_at IS
    'Set on a soft-deleted row once the storage sweep has dealt with its object: deleted it, or found '
    'a live statement_imports row naming the same key. NULL while the row is live or still a sweep '
    'candidate. Set only by StatementStorageSweepService.';

-- The sweep's discovery query, and the per-key update that sets the column above.
CREATE INDEX idx_statement_imports_object_releasable ON statement_imports (object_key, deleted_at)
    WHERE deleted_at IS NOT NULL AND object_key IS NOT NULL AND object_released_at IS NULL;
