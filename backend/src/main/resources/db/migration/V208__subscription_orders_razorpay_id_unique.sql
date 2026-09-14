-- Ambiguous-timeout activation recovery (RazorpayWebhookDispatcher.recoverOrderFromNotes): when
-- BillingCheckoutService's createSubscription call times out on Fynora's end but Razorpay actually
-- created the subscription, no local subscription_orders row exists for it. handleActivated now
-- reconstructs that row from the notes Razorpay echoes back on the activation webhook instead of
-- dropping the event.
--
-- subscription.authenticated and subscription.activated are separate webhook deliveries (separate
-- X-Razorpay-Event-Id values, so WebhookEventService's idempotency ledger does not dedupe between
-- them) that can both reach handleActivated for the same orphaned razorpaySubscriptionId. Without a
-- DB-enforced uniqueness constraint, two concurrent deliveries could each insert their own recovered
-- order row and each run activation's side effects (email, referral trigger, SUBSCRIPTION_CREATED
-- event) once -- a double-processing bug the original "one row already exists in PENDING before any
-- webhook can arrive" design never had to guard against, because that row was always created before
-- checkout() returned. Upgrading the existing non-unique index (V154) closes that window: the
-- loser's INSERT fails loudly (surfaced as a failed webhook delivery Razorpay will retry) instead of
-- silently double-activating.
--
-- Pre-existing duplicates would make CREATE UNIQUE INDEX fail at startup, which on a Flyway
-- migration means the deployment does not come up -- same risk V74/V79/V206 already guard against.
-- Every existing razorpay_subscription_id was assigned by exactly one checkout() or
-- upgradeToNewSubscription() call, so in practice this finds nothing.
DROP INDEX idx_subscription_orders_razorpay_subscription_id;
CREATE UNIQUE INDEX idx_subscription_orders_razorpay_subscription_id
    ON subscription_orders(razorpay_subscription_id) WHERE razorpay_subscription_id IS NOT NULL;

COMMENT ON INDEX idx_subscription_orders_razorpay_subscription_id IS
    'One subscription_orders row per Razorpay subscription id. Prevents two concurrent webhook '
    'deliveries (subscription.authenticated + subscription.activated for the same orphaned '
    'subscription) from each recovering their own order row and double-running activation (V208).';
