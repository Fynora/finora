-- Referral rewards collapse to one milestone: 7 referrals earn a free month of Plus
-- (ReferralService.MILESTONE_REFERRALS). The 3-referral Plus reward is gone and the 7-referral
-- reward is now Plus rather than Premium, because Premium is hidden from sale.
--
-- V207's copy no longer holds:
--   REFERRAL_FRIEND_SUBSCRIBED quoted two counters ("x/3 toward Plus, y/7 toward Premium") and is
--     now sent with a single {{count}}; {{plusCount}}/{{premiumCount}} would render literally.
--   REFERRAL_MILESTONE_REACHED promised that "your progress toward any other reward is
--     untouched" -- there is no other reward -- and rendered {{tier}} as the raw code "PLUS".
--
-- Retire-and-insert, not UPDATE: these rows have been sending to real users since V207, so the old
-- rows stay (inactive) to keep past renders attributable to the copy they used -- see V127's
-- comment on notification_templates.active. REFERRAL_GRANT_ACTIVATED is untouched.
--
-- The dashes in customer-facing strings are real em dashes (U+2014), as in V136.

UPDATE notification_templates
   SET active = false
 WHERE type IN ('REFERRAL_FRIEND_SUBSCRIBED', 'REFERRAL_MILESTONE_REACHED')
   AND active = true;

INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'REFERRAL_FRIEND_SUBSCRIBED', 'EMAIL',
     'A friend just subscribed!',
     'Great news — a friend you referred just subscribed to Fynora. You''re now {{count}}/7 '
     'toward a free month of Fynora Plus.'),
    (gen_random_uuid(), 'REFERRAL_FRIEND_SUBSCRIBED', 'PUSH',
     'Referral progress',
     '{{count}}/7 toward a free month of Plus.'),
    (gen_random_uuid(), 'REFERRAL_MILESTONE_REACHED', 'EMAIL',
     'You earned a reward!',
     'Seven friends you referred have subscribed, so you can redeem 1 month of Fynora Plus for '
     'free. Open Fynora to redeem it now.'),
    (gen_random_uuid(), 'REFERRAL_MILESTONE_REACHED', 'PUSH',
     'Reward unlocked',
     'Redeem your free month of Plus whenever you''re ready.');
