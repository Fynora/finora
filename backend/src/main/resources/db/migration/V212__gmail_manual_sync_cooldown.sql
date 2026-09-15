-- Manual "Sync Now" cooldown, tracked independently of discovery success -- C5.4 follow-up.
--
-- GmailManualSyncService.syncNow's cooldown check reads last_discovery_at, but that column only
-- ever advances on a discovery run that reaches the end of its query window cleanly
-- (GmailMessageDiscoveryService.markDiscovered). A connection whose discovery keeps failing --
-- a Gmail rate limit, an unverified OAuth app's throttled quota -- never has a clean run, so
-- last_discovery_at stays null forever and the cooldown check (`lastDiscovery != null && ...`)
-- can never trigger. The one mailbox most likely to need spam protection is exactly the one that
-- has none: nothing stops "Sync Now" being pressed every second.
--
-- last_manual_sync_attempted_at is set unconditionally at the start of every syncNow call --
-- before discovery or extraction runs, regardless of whether either succeeds -- so the cooldown
-- reflects when a sync was last ATTEMPTED, not when one last completed cleanly.

ALTER TABLE gmail_connections
    ADD COLUMN last_manual_sync_attempted_at TIMESTAMPTZ;
