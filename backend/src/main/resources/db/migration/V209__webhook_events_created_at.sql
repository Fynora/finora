-- Webhook crash-recovery sweep (WebhookEventRecoverySweepService). webhook_events previously had no
-- timestamp at all, so a row stuck with status IS NULL (claim() committed, but the process crashed
-- or was redeployed before dispatch()/markProcessed() ever ran) could not be distinguished from one
-- still legitimately mid-flight. DEFAULT now() backfills existing rows to "now" rather than NULL --
-- any already-stuck row becomes immediately eligible for the sweep's grace-period cutoff instead of
-- being permanently invisible to it.
ALTER TABLE webhook_events ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
