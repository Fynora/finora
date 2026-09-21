-- Gmail sync joins Plus. Product decision (Sid, 2026-09-21): the public page shows Free and Plus
-- only, and Gmail receipts is one of Plus's real benefits. V163 seeded GMAIL_SYNC for PREMIUM only,
-- on the reasoning that it is the one integration with an ongoing per-connection cost (a scheduled
-- worker polls the Gmail API). That cost is bounded per mailbox (see application.yml's Gmail
-- discovery limits), so Plus carries it too. FREE still has no row, and
-- EntitlementService.hasEntitlement fails closed, so Free stays refused.
--
-- Idempotent: feature_entitlements is UNIQUE (plan_id, feature_key) (V99).
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'GMAIL_SYNC', true FROM plans WHERE code = 'PLUS'
ON CONFLICT (plan_id, feature_key) DO NOTHING;
