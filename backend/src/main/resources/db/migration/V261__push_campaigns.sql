-- Admin custom push campaigns: an admin writes any title and message, picks an audience, tests it,
-- then sends now or schedules it (once, or daily at a time of day in IST) and decides when it stops.
--
-- Delivery reuses the notification outbox and dispatcher (NotificationType.CUSTOM_PUSH, PUSH
-- channel only); nothing here talks to FCM. These tables hold what the admin configured, what each
-- run did, and the one-per-day cap.
--
-- No foreign keys to users on purpose, same as audit_logs: created_by, last_tested_by,
-- triggered_by_user and custom_push_daily_cap.user_id are opaque ids kept for attribution, and a
-- raw "DELETE FROM users" (which the E2E isolation spec issues) must not be blocked by a campaign
-- an admin once ran.

CREATE TABLE push_campaigns (
    id               UUID PRIMARY KEY,
    name             VARCHAR(120) NOT NULL,
    title            VARCHAR(80)  NOT NULL,
    message          VARCHAR(240) NOT NULL,
    audience_type    VARCHAR(32)  NOT NULL,
    schedule_kind    VARCHAR(16)  NOT NULL,
    -- ONCE_AT only: the instant it goes out.
    run_at           TIMESTAMPTZ,
    -- DAILY_AT only: time of day in IST (Asia/Kolkata), no date.
    send_time_ist    TIME,
    -- DAILY_AT only, optional: the last IST date a send may happen on.
    ends_on          DATE,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    -- The ONE field the scheduler looks at. NULL unless status is ACTIVE.
    next_run_at      TIMESTAMPTZ,
    last_tested_at   TIMESTAMPTZ,
    last_tested_by   UUID,
    created_by       UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT push_campaigns_audience_valid CHECK (audience_type IN
        ('ALL_WITH_DEVICE', 'NO_STATEMENT_UPLOADED')),
    CONSTRAINT push_campaigns_schedule_valid CHECK (schedule_kind IN
        ('NOW_ONLY', 'ONCE_AT', 'DAILY_AT')),
    CONSTRAINT push_campaigns_status_valid CHECK (status IN
        ('DRAFT', 'ACTIVE', 'PAUSED', 'STOPPED', 'COMPLETED'))
);

-- The scheduler's due-campaign claim touches only ACTIVE rows with a next_run_at.
CREATE INDEX idx_push_campaigns_due ON push_campaigns (next_run_at) WHERE status = 'ACTIVE';

CREATE TABLE push_campaign_runs (
    id                           UUID PRIMARY KEY,
    campaign_id                  UUID         NOT NULL REFERENCES push_campaigns(id) ON DELETE CASCADE,
    -- Which saved version of the campaign this run executed, so support can tie a run to a config
    -- without inferring it from timestamps.
    campaign_version             BIGINT       NOT NULL,
    -- The IST calendar day this run counts as. Also the date part of every per-user key.
    run_date_ist                 DATE         NOT NULL,
    -- The slot the scheduler meant to run at (NULL for send now).
    scheduled_for                TIMESTAMPTZ,
    triggered_by                 VARCHAR(16)  NOT NULL,
    triggered_by_user            UUID,
    started_at                   TIMESTAMPTZ  NOT NULL,
    -- Heartbeat: moved on every page the run queues. A run is "interrupted" (server died) when this
    -- stops moving, not when it merely started a long time ago.
    updated_at                   TIMESTAMPTZ  NOT NULL,
    finished_at                  TIMESTAMPTZ,
    status                       VARCHAR(16)  NOT NULL,
    -- Eligible people when the run started (not an estimate: the real count at run time).
    audience_size                INT          NOT NULL DEFAULT 0,
    queued_count                 INT          NOT NULL DEFAULT 0,
    -- Already got a custom push today from ANOTHER campaign.
    skipped_cap_count            INT          NOT NULL DEFAULT 0,
    -- Already queued by THIS campaign today (a repeat or resumed run).
    skipped_already_queued_count INT          NOT NULL DEFAULT 0,
    -- What was actually sent, frozen: editing the campaign later never rewrites history.
    title_snapshot               VARCHAR(80)  NOT NULL,
    message_snapshot             VARCHAR(240) NOT NULL,
    audience_snapshot            VARCHAR(32)  NOT NULL,
    -- Why a run was MISSED or FAILED. NULL otherwise.
    note                         VARCHAR(300),
    CONSTRAINT push_campaign_runs_trigger_valid CHECK (triggered_by IN ('SCHEDULE', 'ADMIN_NOW')),
    CONSTRAINT push_campaign_runs_status_valid CHECK (status IN
        ('RUNNING', 'DONE', 'FAILED', 'MISSED', 'CANCELLED'))
);

CREATE INDEX idx_push_campaign_runs_campaign ON push_campaign_runs (campaign_id, started_at DESC);
CREATE INDEX idx_push_campaign_runs_running ON push_campaign_runs (updated_at) WHERE status = 'RUNNING';

-- One custom push per person per IST calendar day across ALL campaigns. The row is claimed with
-- INSERT ... ON CONFLICT DO NOTHING before a person is queued, so two campaigns running in the same
-- minute can never both reach the same person. A rolling "24 hours" rule was rejected because it
-- would block a daily campaign from ever sending: yesterday's send is a few seconds under 24 hours
-- old when today's run starts.
--
-- Only today's row enforces anything. Older rows are kept 30 days to answer "why did this person
-- not get it" and then deleted by the campaign scheduler's daily cleanup.
CREATE TABLE custom_push_daily_cap (
    user_id     UUID        NOT NULL,
    day_ist     DATE        NOT NULL,
    campaign_id UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, day_ist)
);

CREATE INDEX idx_custom_push_daily_cap_day ON custom_push_daily_cap (day_ist);

-- Per-campaign delivery counts read the outbox by key prefix (PUSHCAMPAIGN_{campaignId}_{yyyyMMdd}_).
-- text_pattern_ops makes a prefix LIKE use this index, and the type predicate keeps it tiny.
CREATE INDEX idx_notifications_custom_push_key
    ON notifications (notification_key text_pattern_ops)
    WHERE type = 'CUSTOM_PUSH';

-- The dispatcher now claims by priority first (NotificationRepository.claimDue), and the existing
-- idx_notifications_claimable is ordered by next_attempt_at alone, so Postgres could not use it for
-- that ORDER BY. Measured on 100,000 queued rows (a large campaign): 60 ms per claim, a disk-spilling
-- sort of every queued row on every 50-row pass, against 0.1 ms before; with this index, 0.065 ms
-- (an ordered index scan that stops after 50 rows). The expression must stay identical to the CASE
-- in claimDue -- change one and the other, or the planner silently falls back to the sort.
CREATE INDEX idx_notifications_claim_order
    ON notifications ((CASE priority
                           WHEN 'CRITICAL' THEN 0
                           WHEN 'HIGH' THEN 1
                           WHEN 'NORMAL' THEN 2
                           WHEN 'LOW' THEN 3
                           ELSE 4
                       END), next_attempt_at)
    WHERE status IN ('CREATED', 'QUEUED', 'RETRYING');

-- Copy for NotificationType.CUSTOM_PUSH. The title and body ARE the admin's words, substituted for
-- {{title}} and {{message}} (single-pass substitution, so admin text can never inject placeholders).
-- PUSH only: a campaign never sends email or SMS.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'CUSTOM_PUSH', 'PUSH', '{{title}}', '{{message}}');

-- Own permission, not borrowed from the read-only notification dashboard (NOTIFICATION_MANAGE):
-- this one can message every user. ADMIN and SUPER_ADMIN, matching every permission since V24;
-- SUPER_ADMIN's V16 catch-all was a one-time snapshot, so it needs its own explicit grant.
INSERT INTO permissions (name, description) VALUES
    ('PUSH_CAMPAIGN_MANAGE',
     'Create, test, schedule, send, pause and stop admin push notification campaigns. Can send a '
     'push notification of the admin''s own wording to users.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'PUSH_CAMPAIGN_MANAGE';

-- Master off switch. On by default: with no campaign created or started, nothing sends.
INSERT INTO feature_flags (key, description, enabled) VALUES
    ('PUSH_CAMPAIGNS_ENABLED',
     'Master switch for admin push campaigns. When off, no scheduled run fires and send now / send '
     'test are refused; a campaign that comes due while it is off is handled by the missed-run rule '
     '(sent late if within 2 hours, otherwise recorded as missed).',
     true);
