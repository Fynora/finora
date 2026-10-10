-- When the one-time "hold overdue" admin escalation was sent for the job's CURRENT hold.
-- Cleared every time the job enters a hold, so a re-held job gets its own 48 hours and its own
-- escalation (Gate 1 spec §4). Null = not escalated.
ALTER TABLE import_jobs ADD COLUMN overdue_alerted_at TIMESTAMPTZ;
