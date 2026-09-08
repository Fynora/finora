-- Bug found on review: InvoiceService read planName/billingCycle off the CURRENT subscriptions row
-- (via payments.subscription_id) rather than what was actually true at charge time. Subscriptions
-- is mutated in place (RazorpayWebhookDispatcher.handleCharged/handleActivated), so a payment made
-- while on Plus, viewed again after the user later upgrades to Premium, would silently show
-- "Premium" on what was genuinely a Plus invoice -- same staleness problem the membership-period
-- calculation was already fixed for (see InvoiceService's own class doc), just not caught there
-- for plan/cycle too.
--
-- Nullable, and deliberately left NULL for every existing row: there is no reliable way to
-- reconstruct which plan/cycle a past payment was actually for (no historical record exists
-- anywhere in this schema), so backfilling would be guessing, not fact. InvoiceService falls back
-- to the current live-subscription lookup for any row where these are NULL -- unchanged (and still
-- imperfect) behavior for pre-migration payments, correct behavior for every payment from here on.
ALTER TABLE payments ADD COLUMN plan_id UUID REFERENCES plans(id);
ALTER TABLE payments ADD COLUMN billing_cycle VARCHAR(10);
