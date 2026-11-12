-- Each blanked copy made of an upload of a layout flagged for review (FlaggedLayoutUploadService).
--
-- Making one tells the customer, by push and email, that our team opened their statement to fix a
-- reading problem. The import timeline is where they can look that up later, and it lists those
-- days from statement_original_views alone, so a copy needs a row of its own. Not the audit log:
-- audit_logs has no index on entity_id, and the timeline is read by customers.
--
-- Only copies that were made are recorded; one the privacy scan refused reached nobody and told
-- nobody. Cascades off import_jobs like statement_original_views (V271), so an account purge,
-- which deletes the user's import jobs, removes these rows with them.
CREATE TABLE layout_upload_blanked_copies (
    id                  UUID        PRIMARY KEY,
    job_id              UUID        NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    subject_user_id     UUID        NOT NULL,
    made_by             UUID        NOT NULL,
    analysis_reference  VARCHAR(24) NOT NULL,
    made_at             TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_layout_upload_blanked_copies_job ON layout_upload_blanked_copies (job_id, made_at);
