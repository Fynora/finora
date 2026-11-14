-- Background jobs health part 2 (docs/superpowers/specs/2026-11-14-background-jobs-github-design.md).
-- GitHub workflows are read every 15 minutes; a run is recorded once, by its GitHub run id, so a
-- failed nightly run makes one failure row and one email rather than one every poll. The run's URL
-- lets the page link to it.
ALTER TABLE background_job_status
    ADD COLUMN last_external_run_id BIGINT,
    ADD COLUMN last_external_url    TEXT;
