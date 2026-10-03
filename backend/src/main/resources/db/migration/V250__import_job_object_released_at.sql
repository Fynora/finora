-- When an import job stopped holding its stored object.
--
-- An asynchronous upload writes its own object (ImportJobService.accept), and that object is never
-- the one a confirmed statement points at: confirming stores the bytes again, compressed and
-- encrypted under a fresh IV, so the two keys differ even for identical bytes. The storage sweep
-- only ever discovered candidates from soft-deleted statement_imports rows, so a COMPLETED or
-- CANCELLED job's object was never a candidate at all and was kept forever.
--
-- StatementStorageSweepService now also discovers COMPLETED/CANCELLED jobs past the retention
-- window. This column is set once that job's claim on its object has ended -- the object was
-- deleted, or another live row still names the same key and now owns its lifecycle. Without it a
-- job would stay a candidate forever after its object was gone and, oldest first under a batch
-- limit, crowd every newer candidate out of each run.
ALTER TABLE import_jobs ADD COLUMN object_released_at TIMESTAMPTZ;

COMMENT ON COLUMN import_jobs.object_released_at IS
    'When this job stopped holding its stored object: the storage sweep deleted it, or found another '
    'live row naming the same key. NULL while the job still holds it. Set only for COMPLETED and '
    'CANCELLED jobs, by StatementStorageSweepService.';

-- The sweep's discovery query, oldest finished first.
CREATE INDEX idx_import_jobs_object_releasable ON import_jobs (finished_at)
    WHERE status IN ('COMPLETED', 'CANCELLED') AND object_key IS NOT NULL AND object_released_at IS NULL;
