-- Festival app icon schedule: which home-screen icon the mobile app should show, and when.
--
-- Each row is one planned window: an icon key (from the fixed list in AppIcon.java, so an admin can
-- only pick an icon the installed app actually bundles), a start and an end instant, and a label
-- such as "Diwali 2026". Outside every window the app shows its default icon, so a window ending is
-- the automatic revert: nothing has to run at the end time.
--
-- icon_key has no CHECK list on purpose. Adding an icon is a code change made together with the
-- app build that bundles it; a CHECK here would also need a migration for every new key.
--
-- Windows never overlap. The service refuses an overlapping save with a clear message, and the
-- exclusion constraint below is what makes that hold when two saves race. A window covers
-- [starts_at, ends_at): one may start at the exact instant another ends.
--
-- created_by / updated_by are admin user ids kept for attribution, with no foreign key, same as
-- push_campaigns and audit_logs.

CREATE TABLE app_icon_schedule (
    id          UUID PRIMARY KEY,
    icon_key    VARCHAR(32)  NOT NULL,
    label       VARCHAR(80)  NOT NULL,
    starts_at   TIMESTAMPTZ  NOT NULL,
    ends_at     TIMESTAMPTZ  NOT NULL,
    created_by  UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_by  UUID         NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT app_icon_schedule_ends_after_start CHECK (ends_at > starts_at),
    CONSTRAINT app_icon_schedule_no_overlap
        EXCLUDE USING gist (tstzrange(starts_at, ends_at, '[)') WITH &&)
);
