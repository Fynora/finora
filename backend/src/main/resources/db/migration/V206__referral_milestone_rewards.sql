-- Referral milestone rewards (design spec at docs/superpowers/specs/
-- 2026-09-14-referral-milestone-rewards-design.md). Replaces cash as the default referral reward
-- with free subscription time -- ReferralService.creditReward/wallet_ledger (V168) are untouched
-- and stay available as a dormant, admin-only fallback.

-- Two INDEPENDENT counters, not one shared counter (design spec section 2, revised after product
-- review: the original single-counter design forced an either/or choice at 3 that discarded
-- progress toward 7 if the user redeemed early). Both increment together on the same event (a
-- referral reaching SUBSCRIBED, see ReferralService.incrementMilestoneCountersIfEligible); each
-- resets to 0 ONLY when its own tier is redeemed, so redeeming Plus never costs any progress
-- toward Premium and vice versa. Both live on referral_codes (one row per referrer already)
-- rather than being computed by scanning referrals on every read.
ALTER TABLE referral_codes ADD COLUMN plus_milestone_counter INT NOT NULL DEFAULT 0;
ALTER TABLE referral_codes ADD COLUMN premium_milestone_counter INT NOT NULL DEFAULT 0;
COMMENT ON COLUMN referral_codes.plus_milestone_counter IS
    'Referrals reaching SUBSCRIBED since Plus was last redeemed (or ever, if never redeemed). Resets to 0 only when Plus is redeemed -- never affected by redeeming Premium.';
COMMENT ON COLUMN referral_codes.premium_milestone_counter IS
    'Referrals reaching SUBSCRIBED since Premium was last redeemed (or ever, if never redeemed). Resets to 0 only when Premium is redeemed -- never affected by redeeming Plus.';

-- A referral-earned free month of Plus or Premium -- an overlay EntitlementService checks
-- alongside the user's real subscription, never a mutation of subscriptions' own billing fields
-- (spec section 3: Fynora has no API to touch a RevenueCat-billed user's renewal date, so the
-- grant has to work identically regardless of payment provider). Not soft-deletable: a
-- state-tracking row updated in place by ReferralGrantSweepService's one-directional
-- PENDING -> ACTIVE -> EXPIRED transitions, same reasoning as referrals' own lack of a
-- deleted_at/version pair.
CREATE TABLE referral_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    tier VARCHAR(10) NOT NULL,
    status VARCHAR(10) NOT NULL,
    -- ON DELETE CASCADE: AccountPurgeSweepService hard-deletes a purged user's own referrals
    -- (ReferralRepository.deleteByReferrerUserId) -- this row always points to one of THAT same
    -- user's referrals (see ReferralService.redeemMilestone), so it must not survive as a dangling
    -- FK once the referral it points to is gone. The purge sweep also calls
    -- ReferralGrantRepository.deleteByUserId directly as the primary cleanup path, same as every
    -- other user-owned table in this codebase -- this CASCADE is a backstop, not the intended path.
    earned_from_referral_id UUID NOT NULL REFERENCES referrals(id) ON DELETE CASCADE,
    activated_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_referral_grants_user_id ON referral_grants(user_id);
-- ReferralGrantSweepService's own candidate queries: all PENDING grants (to find who has one to
-- activate) and all ACTIVE grants past expiry (to expire them).
CREATE INDEX idx_referral_grants_status ON referral_grants(status);
-- At most one ACTIVE grant per user at a time -- enforced by the database, same reasoning as
-- subscriptions' own idx_subscriptions_one_active_per_user (V99): the sweep's own re-check logic
-- assumes this invariant, not just trusts it.
CREATE UNIQUE INDEX idx_referral_grants_one_active_per_user ON referral_grants(user_id)
    WHERE status = 'ACTIVE';

COMMENT ON COLUMN referral_grants.tier IS 'PLUS or PREMIUM -- see ReferralGrant.TIER_PLUS/TIER_PREMIUM.';
COMMENT ON COLUMN referral_grants.status IS 'PENDING, ACTIVE, or EXPIRED -- see ReferralGrant.STATUS_*.';
COMMENT ON COLUMN referral_grants.earned_from_referral_id IS
    'Informational only -- which referral pushed a counter to its threshold. Never read by ReferralGrantSweepService or EntitlementService.';

-- Notification copy for the three new types (see NotificationType's own doc comment: a type with
-- no active template row here cannot be rendered). {{plusCount}}/{{premiumCount}}/{{tier}}/
-- {{expiresAt}} are plain {{placeholder}} substitution, same as every other row in this table.
-- plusCount/premiumCount are the two INDEPENDENT counters (design spec section 2) -- both always
-- present together, since the two tiers no longer share one progress track.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'REFERRAL_FRIEND_SUBSCRIBED', 'EMAIL',
     'A friend just subscribed!',
     'Great news — a friend you referred just subscribed to Fynora. You''re now {{plusCount}}/3 '
     'toward your next Plus reward and {{premiumCount}}/7 toward your next Premium reward.'),
    (gen_random_uuid(), 'REFERRAL_FRIEND_SUBSCRIBED', 'PUSH',
     'Referral progress',
     '{{plusCount}}/3 toward Plus, {{premiumCount}}/7 toward Premium.'),
    (gen_random_uuid(), 'REFERRAL_MILESTONE_REACHED', 'EMAIL',
     'You earned a reward!',
     'You''ve referred enough friends to redeem 1 month of Fynora {{tier}} for free. Open Fynora to '
     'redeem it now -- your progress toward any other reward is untouched.'),
    (gen_random_uuid(), 'REFERRAL_MILESTONE_REACHED', 'PUSH',
     'Reward unlocked',
     'Redeem 1 month of {{tier}} whenever you''re ready.'),
    (gen_random_uuid(), 'REFERRAL_GRANT_ACTIVATED', 'EMAIL',
     'Your free month of {{tier}} is active',
     'Your free month of Fynora {{tier}} is now active, until {{expiresAt}}. Enjoy the extra '
     'features!'),
    (gen_random_uuid(), 'REFERRAL_GRANT_ACTIVATED', 'PUSH',
     '{{tier}} is active',
     'Your free month of {{tier}} runs until {{expiresAt}}.');
