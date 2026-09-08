-- Referral reward ledger restore. ReferralService.creditReward checks referrals.status = 'SUBSCRIBED'
-- then inserts a wallet_ledger row -- a classic check-then-act race: two concurrent admin credit
-- requests for the same referral (a double-click, a retried request, two admins on the same row)
-- can both read SUBSCRIBED before either commits, and both insert, double-crediting the wallet.
--
-- Same class of bug this codebase already hit and fixed twice (NotificationRepository.
-- insertIfAbsent, MerchantAliasRepository.insertIfAbsent) -- a plain application-level check is not
-- enough, and catching DataIntegrityViolationException after a plain save() does not work either
-- (once one statement in a Postgres transaction fails, every later statement on it fails too, so
-- catching the exception in Java does not by itself keep the rest of that transaction usable). The
-- fix here follows the same pattern: a real database constraint, enforced with
-- INSERT ... ON CONFLICT DO NOTHING (WalletLedgerRepository.insertReferralRewardIfAbsent) rather
-- than save() + a Java-side check.
--
-- Partial (not a plain UNIQUE on reference_id) because reference_id is reused loosely across
-- future reasons (WalletLedgerEntry's own doc comment: "the referral (or other future event) that
-- caused this entry") -- only REFERRAL_REWARD rows need this invariant, one credit per referral,
-- ever.
--
-- Pre-flight check below, not just the bare CREATE UNIQUE INDEX: the ORIGINAL reward-crediting
-- feature (D-28 PR4-C, #232) was live in production for about two weeks (2026-08-22 to
-- 2026-09-05) with this EXACT unprotected race before being descoped -- so it is a real
-- possibility, not a hypothetical, that a production wallet_ledger already has two REFERRAL_REWARD
-- rows sharing one reference_id from that window. Left as a bare CREATE UNIQUE INDEX, that would
-- surface as Postgres's generic "could not create unique index... Key (reference_id)=(...) is
-- duplicated" -- true, but it doesn't say how many referrals are affected or point at what to do
-- about it. This raises a clear, actionable error instead, so a failed deploy is diagnosable
-- immediately rather than requiring someone to go find and interpret the raw constraint-violation
-- error by hand.
DO $$
DECLARE
    duplicate_referral_count integer;
BEGIN
    SELECT COUNT(*) INTO duplicate_referral_count FROM (
        SELECT reference_id
        FROM wallet_ledger
        WHERE reason = 'REFERRAL_REWARD'
        GROUP BY reference_id
        HAVING COUNT(*) > 1
    ) duplicates;

    IF duplicate_referral_count > 0 THEN
        RAISE EXCEPTION 'V168 cannot apply: % referral(s) already have more than one REFERRAL_REWARD wallet_ledger row for the same reference_id. This must be resolved by hand -- for each affected reference_id, decide which row is the correct credit and delete or adjust the other(s) -- before this migration can run. See this migration''s own comment for why duplicates are a real possibility here, not just a hypothetical.', duplicate_referral_count;
    END IF;
END $$;

CREATE UNIQUE INDEX uq_wallet_ledger_referral_reward_reference
    ON wallet_ledger (reference_id)
    WHERE reason = 'REFERRAL_REWARD';
