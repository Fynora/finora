-- Admin-controlled daily limit for campaign pushes (follow-up to V261, which fixed it at one).
--
-- A person's day is now a set of numbered slots instead of a single row. A campaign claims the
-- lowest free slot below the current limit; when none is free the person has reached the limit.
-- Claiming stays one atomic INSERT ... ON CONFLICT DO NOTHING per slot, so two campaigns (or two
-- servers) running in the same minute still cannot both take the same slot, with no lock and no
-- count-then-insert race. Every V261 row becomes slot 1, so the one-a-day behaviour that was live
-- is exactly what the default limit of 1 reproduces.
--
-- The second constraint matters as much as the first: one campaign takes at most one slot per
-- person per day. Without it, a resumed or repeated run of the same campaign would use up a
-- second slot on a person it had already queued, and that slot would be wasted (the outbox key
-- refuses the duplicate row).

ALTER TABLE custom_push_daily_cap ADD COLUMN slot SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE custom_push_daily_cap DROP CONSTRAINT custom_push_daily_cap_pkey;
ALTER TABLE custom_push_daily_cap ADD PRIMARY KEY (user_id, day_ist, slot);
ALTER TABLE custom_push_daily_cap
    ADD CONSTRAINT uq_custom_push_daily_cap_campaign UNIQUE (user_id, day_ist, campaign_id);

-- One row, always id 1: the limit an admin sets from the Push Campaigns screen. The bounds are
-- enforced here as well as in the API so no path can store a typo like 100 or 0. Raising the upper
-- bound later is a one-line migration.
CREATE TABLE push_campaign_settings (
    id                       SMALLINT    PRIMARY KEY CHECK (id = 1),
    daily_limit_per_person   SMALLINT    NOT NULL DEFAULT 1
                                         CHECK (daily_limit_per_person BETWEEN 1 AND 10),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Opaque admin id for attribution; no foreign key, same reasoning as the V261 tables.
    updated_by               UUID
);

INSERT INTO push_campaign_settings (id, daily_limit_per_person) VALUES (1, 1);
