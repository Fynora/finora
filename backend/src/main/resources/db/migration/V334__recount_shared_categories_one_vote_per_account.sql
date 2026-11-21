-- Audit F-12 (2026-11-20). A shop's shared category became "trusted" on a count of every recorded
-- vote, and one account could record as many as it liked: one account voting ten times beside two
-- accounts that agreed with each other took 83% and set the suggestion for everyone.
-- SharedCorpusService.computeTier now counts one vote per account, its latest.
--
-- That fixes every count made from now on. A row that already exists is only counted again when a
-- new vote arrives for it. So a row made trusted by repeat votes would keep suggesting its
-- category until then, and a row kept disputed by one account's repeat votes against three that
-- agree would stay unsuggested. This sends both kinds to be counted again.
--
-- Which rows: a trusted or disputed row where some account has more than one vote. Where every
-- account voted once, the old count and the new one are the same, and the row is left alone.
--
-- How: the row is marked as revalidating since 91 days ago. Revalidating rows are never suggested,
-- and the nightly sweep (SharedCorpusRevalidationSweepService, 03:45) counts again every row that
-- has been revalidating for 90 days or more, with the new rule. A row that three accounts really
-- agree on is trusted after that run; the others become disputed or are removed. A new vote for
-- the shop before the sweep runs counts it again at once.
--
-- Safe to run twice: after the first run no row it touched is trusted or disputed.

UPDATE shared_merchant_category c
SET status = 'REVALIDATING',
    revalidating_since = now() - INTERVAL '91 days',
    last_recomputed_at = now()
WHERE c.status IN ('TRUSTED', 'DISPUTED')
  AND EXISTS (
      SELECT 1
      FROM counterparty_category_observation o
      WHERE o.counterparty_key = c.counterparty_key
        AND o.direction = c.direction
      GROUP BY o.user_id
      HAVING count(*) > 1
  );
