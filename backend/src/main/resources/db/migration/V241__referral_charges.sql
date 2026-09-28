-- The charge that moved each referral from REGISTERED to SUBSCRIBED, so a refund or a lost
-- chargeback of that exact charge can take the referral back (ReferralService.onChargeReversed).
--
-- Why a table of its own rather than a column on referrals or a lookup through payments: the
-- account purge (AccountPurgeSweepService) hard-deletes the referred user's referral row and their
-- payments. "Pay, get counted, delete the account, get refunded" would then leave nothing linking
-- the refund back to the referrer, and the count would stay. This row keeps only the referrer, the
-- provider and the provider's own charge id -- no column identifies the referred user -- so it is
-- left in place when the referred user is purged (referral_id goes NULL) and removed when the
-- referrer is purged.
--
-- counted: whether this charge actually added 1 to the referrer's milestone counter. A referral
-- whose two accounts share a device/IP still becomes SUBSCRIBED but is not counted, and reversing
-- it must then not take 1 off.
--
-- reversed_at: set once, by the first refund/chargeback of this charge. The locked read of it is
-- what makes a repeated or concurrent reversal a no-op.
--
-- referrer_user_id is NULL only on a row written by a refund that arrived BEFORE the charge it
-- refunds had been processed (neither provider guarantees webhook order, and a charge that raced
-- ahead of its subscription's activation waits for the recovery sweep). That row is born
-- reversed, so the late charge finds its id already taken and never counts.
CREATE TABLE referral_charges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    referrer_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    referral_id UUID REFERENCES referrals(id) ON DELETE SET NULL,
    provider VARCHAR(20) NOT NULL,
    charge_ref VARCHAR(255) NOT NULL,
    counted BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reversed_at TIMESTAMPTZ,
    reversal_reason VARCHAR(40),
    CONSTRAINT uq_referral_charges_provider_charge_ref UNIQUE (provider, charge_ref)
);
CREATE INDEX idx_referral_charges_referrer_user_id ON referral_charges(referrer_user_id);
CREATE INDEX idx_referral_charges_referral_id ON referral_charges(referral_id);

-- The store transaction of the subscription's latest paid period (RevenueCat INITIAL_PURCHASE or
-- RENEWAL transaction_id). A refund of exactly this transaction took the current period away, so
-- paid access ends then; a refund of any older transaction leaves the current period alone. The
-- expiry date on a refund cannot tell the two apart: RevenueCat moves the refunded period's
-- expiration_at_ms back to the refund time (its own sample refund event shows that).
ALTER TABLE subscriptions ADD COLUMN revenuecat_latest_transaction_id VARCHAR(255);

-- REFERRAL_REVERSED (NotificationType): tells the referrer a friend's counted payment was refunded
-- or charged back, so that referral no longer counts. {{count}} is the progress shown in the app,
-- never below 0 (ReferralService.onChargeReversed). The dash is a real em dash (U+2014), as in V230.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'REFERRAL_REVERSED', 'EMAIL',
     'A referral no longer counts',
     'A payment from a friend you referred was refunded, so that referral no longer counts toward '
     'your free month of Fynora Plus. You''re now at {{count}}/7.'),
    (gen_random_uuid(), 'REFERRAL_REVERSED', 'PUSH',
     'Referral refunded',
     'A friend''s payment was refunded — you''re now at {{count}}/7.');
