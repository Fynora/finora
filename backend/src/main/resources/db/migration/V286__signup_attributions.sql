-- Where a sign-up came from, so a marketing channel (search, a reel, a directory listing, a link
-- in a message) can be measured by the accounts it brought in. Written once, in the registration
-- transaction, for a self-service sign-up on the website: the password form and the Google/Apple
-- buttons alike. Only when the browser had anything to report -- a visit typed in directly, or a
-- mobile-app sign-up, leaves no row, and the counts read that as "direct".
--
-- referrer_host is the host name of the page that linked here, never its path or query: a full
-- referrer URL can carry another site's session tokens, and the host is all the question needs.
-- The four utm_* columns are the campaign tags on the landing link, lower-cased so that one source
-- spelt two ways counts once, capped at 100 characters each (SignupAttributionService truncates
-- before the insert).
--
-- Keyed by user_id, one row per account. ON DELETE CASCADE the way insight_acknowledgements is,
-- and the account purge deletes the row explicitly too (it anonymizes the users row rather than
-- deleting it, so the cascade never fires there -- see AccountPurgeSweepService). The privacy
-- page says this is kept with the account and deleted with it.
CREATE TABLE signup_attributions (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    referrer_host VARCHAR(253),
    utm_source VARCHAR(100),
    utm_medium VARCHAR(100),
    utm_campaign VARCHAR(100),
    utm_content VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT signup_attributions_not_empty CHECK (
        referrer_host IS NOT NULL OR utm_source IS NOT NULL OR utm_medium IS NOT NULL
        OR utm_campaign IS NOT NULL OR utm_content IS NOT NULL)
);
-- For the admin "sign-ups by source" count, which groups on these two.
CREATE INDEX idx_signup_attributions_source ON signup_attributions(utm_source, referrer_host);
