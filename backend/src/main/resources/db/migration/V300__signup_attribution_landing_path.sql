-- Which public page a sign-up landed on, so the SEO pages (bank pages, Help answers, the narration
-- glossary) can each be measured by the accounts they bring in. V286 stored where a visit came FROM
-- (the referring site, the campaign tags); this stores where it came TO.
--
-- landing_path is the path of the first public Fynora page of the visit, e.g. '/help' or
-- '/banks/example-bank' -- the path only: no query string, no fragment, no host. The website takes it
-- from a marker the build writes into each prerendered public page, not from the address bar, so only
-- a page that really exists as a public page can be recorded (never a typo, a token in the path or an
-- address someone made up); SignupAttributionService re-checks the shape and drops anything else.
-- 200 characters is far more than any page path; a longer value is dropped, not cut, since a cut
-- path names a different page.
--
-- Same rules as the rest of the row: one per account, deleted with it (ON DELETE CASCADE from V286,
-- and the account purge deletes it explicitly), nothing stored for the mobile apps, which count as
-- "direct". A sign-up before this migration has no landing path; the activation report counts only
-- accounts made after it (flyway_schema_history 'installed_on' for this version).
--
-- The row-is-never-empty CHECK from V286 learns about the new column, so a row holding only a
-- landing path (a visitor who arrived directly on a page, with no referrer and no tags) is allowed.
ALTER TABLE signup_attributions ADD COLUMN landing_path VARCHAR(200);

ALTER TABLE signup_attributions DROP CONSTRAINT signup_attributions_not_empty;
ALTER TABLE signup_attributions ADD CONSTRAINT signup_attributions_not_empty CHECK (
    referrer_host IS NOT NULL OR utm_source IS NOT NULL OR utm_medium IS NOT NULL
    OR utm_campaign IS NOT NULL OR utm_content IS NOT NULL OR landing_path IS NOT NULL);

ALTER TABLE signup_attributions ADD CONSTRAINT signup_attributions_landing_path_shape CHECK (
    landing_path IS NULL OR landing_path ~ '^/([a-z0-9_~-][a-z0-9._~-]*(/[a-z0-9_~-][a-z0-9._~-]*)*)?$');
