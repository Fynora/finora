-- How many times the recovery sweep has retried a failed billing event.
--
-- WebhookEventRecoverySweepService retries every FAILED event on each run, oldest first, a fixed
-- number per run, and until now with no limit on how often. An event that can never succeed
-- therefore kept its place at the front of that queue for good. Enough of them and a run spent
-- its whole allowance on events that cannot succeed, and a newer event that failed for a passing
-- reason (a renewal that arrived before its activation, which is what the retry exists for) was
-- never reached.
--
-- With this count the sweep takes the least-retried events first and stops retrying one once it
-- reaches the limit (app.webhook-event-recovery.sweep.max-attempts). Such an event stays FAILED,
-- which is what marks it for a person to look at.
--
-- Existing rows start at 0, so anything already stuck gets its full allowance of further tries
-- before the sweep leaves it alone.
ALTER TABLE webhook_events
    ADD COLUMN recovery_attempts INTEGER NOT NULL DEFAULT 0;
