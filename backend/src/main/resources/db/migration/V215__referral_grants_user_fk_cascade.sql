-- referral_grants.user_id (V207) referenced users(id) with the default NO ACTION -- unlike this
-- same table's own earned_from_referral_id FK a few lines below it, which already got ON DELETE
-- CASCADE. Caught by e2e/tests/workflow/isolation.spec.ts's "records which user_id foreign keys
-- still block account deletion" diagnostic, live-failing on the nightly suite since V207 landed.
--
-- referral_grants is user-owned state, not an audit trail (same reasoning V157/V165 already
-- applied to subscription_orders/user_financial_focus/user_checklist_events): the row means
-- nothing once the user it belongs to is gone. AccountPurgeSweepService already calls
-- ReferralGrantRepository.deleteByUserId as its primary cleanup path (V207's own table comment),
-- so this CASCADE is a backstop against a raw DELETE FROM users bypassing that, matching the same
-- backstop role earned_from_referral_id's CASCADE already plays for the referrals table.
ALTER TABLE referral_grants DROP CONSTRAINT referral_grants_user_id_fkey;
ALTER TABLE referral_grants ADD CONSTRAINT referral_grants_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
