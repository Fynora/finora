-- Layout review queue and layout profiles (versions).
--
-- WHY
-- ---
-- A newer Kotak credit-card layout reached production with every transaction description blank and
-- two verification warnings, and no admin heard about it: the layout registry (V68) only records a
-- layout once an import is CONFIRMED, and the user stopped at the review screen. Nothing flagged a
-- layout the engine had never seen, or one that staged badly. And when a bank changes its format
-- there is no way to say "this is the next version of the layout we already know" -- every
-- fingerprint is an unrelated row.
--
-- WHAT
-- ----
-- 1. layout_registry gains a review flag, written at STAGING time: needs_review, the reasons
--    (comma-separated codes), when it was flagged and which analysis session flagged it. Staging
--    also upserts the row, so a layout is registered the first time it is seen, not only once an
--    import is confirmed. staging_count counts those sightings separately so observation_count keeps
--    meaning "confirmed imports", which is what every existing reader of it assumes.
-- 2. layout_profile: an operator-named family of layouts ("Kotak Credit Card"). A registry row can be
--    linked to one profile with a version number; versions are unique within a profile. Linking is
--    always an operator action -- the engine never guesses which family a new fingerprint belongs to.
-- 3. LAYOUT_REGISTRY_MANAGE: its own permission for curating layouts and receiving review alerts,
--    the same reasoning V135 applied to IMPORT_TRIAGE_MANAGE.

CREATE TABLE layout_profile (
    id          UUID         PRIMARY KEY,
    name        TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Case-insensitive: "Kotak Credit Card" and "kotak credit card" are one family to a reader.
CREATE UNIQUE INDEX ux_layout_profile_name ON layout_profile (lower(name));

ALTER TABLE layout_registry
    ADD COLUMN staging_count              BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN needs_review               BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN review_reasons             TEXT,
    ADD COLUMN review_flagged_at          TIMESTAMPTZ,
    ADD COLUMN review_analysis_reference  VARCHAR(24),
    -- Reasons an operator has already looked at and resolved. A later staging re-flags the layout
    -- only for a reason NOT in this set, so a layout that always prints (say) a totals warning is
    -- reviewed once rather than re-alerting on every upload after each resolve.
    ADD COLUMN acknowledged_reasons       TEXT,
    ADD COLUMN profile_id                 UUID         REFERENCES layout_profile (id),
    ADD COLUMN profile_version            INTEGER,
    ADD CONSTRAINT ck_layout_registry_profile_version
        CHECK ((profile_id IS NULL) = (profile_version IS NULL) AND (profile_version IS NULL OR profile_version > 0));

CREATE UNIQUE INDEX ux_layout_registry_profile_version
    ON layout_registry (profile_id, profile_version) WHERE profile_id IS NOT NULL;

-- The review queue is read by status of the flag; tiny table, but the partial index keeps the
-- queue query from ever scanning reviewed rows.
CREATE INDEX ix_layout_registry_needs_review ON layout_registry (review_flagged_at) WHERE needs_review;

INSERT INTO permissions (name, description) VALUES
    ('LAYOUT_REGISTRY_MANAGE',
     'Curate statement layouts: resolve the layout review queue, name layouts, set their support '
     'status and group them into versioned layout profiles. Also receives layout review alerts. '
     'Grants no access to statement content.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'LAYOUT_REGISTRY_MANAGE';
