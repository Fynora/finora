-- Automatic layout profile grouping (follows V243).
--
-- V243 made layout profiles an operator-only action: every layout had to be added to a profile by
-- hand, including the existing ones. The engine already knows, for most statements, which bank and
-- which kind of account a layout belongs to (the detected bank and product at staging, and the
-- account a confirmed import went into). This lets it place a layout into the matching profile on
-- its own, as that profile's next version.
--
-- layout_profile.auto_key  -- the grouping key an automatically created (or adopted) profile
--                             answers to: "<bank id>|<account families>", e.g. "KOTAK|CREDIT_CARD".
--                             Null for a profile only an operator uses.
-- layout_registry.profile_link_source
--                          -- who decided this layout's profile: AUTO (the engine) or MANUAL (an
--                             operator). NULL means nobody has decided yet. The engine only ever
--                             links a layout whose source is NULL, so an operator's link -- and an
--                             operator's decision to take a layout OUT of a profile -- is never
--                             overridden.

ALTER TABLE layout_profile ADD COLUMN auto_key VARCHAR(96);
CREATE UNIQUE INDEX ux_layout_profile_auto_key ON layout_profile (auto_key) WHERE auto_key IS NOT NULL;

ALTER TABLE layout_registry
    ADD COLUMN profile_link_source VARCHAR(8),
    ADD CONSTRAINT ck_layout_registry_profile_link_source
        CHECK (profile_link_source IS NULL OR profile_link_source IN ('AUTO', 'MANUAL'));

-- Every link that already exists was made by an operator (V243 had no automatic path).
UPDATE layout_registry SET profile_link_source = 'MANUAL' WHERE profile_id IS NOT NULL;
