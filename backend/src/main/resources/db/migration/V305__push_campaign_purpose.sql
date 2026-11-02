-- A push campaign's purpose (2026-11-02). Every campaign went out as a FINANCIAL notification, the
-- category users can only switch off, so a promotional campaign reached people who never agreed
-- to marketing. PROMOTIONAL campaigns go out as MARKETING, which is off unless the user turns it
-- on, and reach only users who did. Existing campaigns and runs stay OPERATIONAL: what they send
-- and who they reach do not change.
ALTER TABLE push_campaigns
    ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'OPERATIONAL',
    ADD CONSTRAINT push_campaigns_purpose_valid CHECK (purpose IN ('OPERATIONAL', 'PROMOTIONAL'));

-- Copied onto each run with the title, message and audience, so a run sends what it started with.
ALTER TABLE push_campaign_runs
    ADD COLUMN purpose_snapshot VARCHAR(16) NOT NULL DEFAULT 'OPERATIONAL',
    ADD CONSTRAINT push_campaign_runs_purpose_valid CHECK (purpose_snapshot IN ('OPERATIONAL', 'PROMOTIONAL'));
