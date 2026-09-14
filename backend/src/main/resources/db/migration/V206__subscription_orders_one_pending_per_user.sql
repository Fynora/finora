-- Closes a checkout() double-submit race: two concurrent first-time checkouts for a user with no
-- existing subscription/order both pass the check-then-act PENDING-order guard in
-- BillingCheckoutService.resumableOrderOrGuard() before either INSERT commits, each creating a
-- real, separate Razorpay subscription. idx_subscriptions_one_active_per_user (V99) only guards
-- once a webhook activates a row -- nothing DB-enforced blocks the race at checkout time itself.
--
-- Pre-existing duplicates would make CREATE UNIQUE INDEX fail at startup, which on a Flyway
-- migration means the deployment does not come up -- same risk V74/V79 already guard against for
-- import_jobs/import_sessions. This race has been live since V154 shipped subscription billing
-- V1, so unlike V74's "in practice this finds nothing" this is not something to bet a deploy on.
-- Older duplicates are superseded (ABANDONED) rather than deleted, mirroring V74's exact choice:
-- an abandoned order is part of the user's billing history (support/funnel visibility, per
-- SubscriptionOrder's own class doc) and the newest PENDING row is the checkout the user is
-- actually waiting on. Deliberately not COMPLETED and not completed_at -- that status/column pair
-- belongs only to a real Razorpay activation (RazorpayWebhookDispatcher.handleActivated), which
-- this migration has no evidence one way or the other for.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at DESC, id) AS rn
      FROM subscription_orders
     WHERE status = 'PENDING'
)
UPDATE subscription_orders
   SET status = 'ABANDONED'
 WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

CREATE UNIQUE INDEX idx_subscription_orders_one_pending_per_user ON subscription_orders(user_id)
    WHERE status = 'PENDING';

COMMENT ON INDEX idx_subscription_orders_one_pending_per_user IS
    'One PENDING checkout per user. Closes the checkout() double-submit race (V206); the loser''s '
    'INSERT hits this constraint and GlobalExceptionHandler answers it as 409, same pattern as '
    'V74/V79.';
