-- Every change a user makes to a notification switch, one row each, never updated (2026-11-02).
--
-- notification_preferences keeps only the current on/off. That cannot show when a user turned
-- "Offers and news" (MARKETING on PUSH, V305) on, from which app, or against which wording -- and
-- under DPDP Act s.6(10) Fynora has to prove that consent was given. This is that record, for every
-- switch, so an opt-out is on record the same way.
--
--   client:         the app the change came from (X-Client-Platform, advisory), or UNKNOWN.
--   notice_version: which wording of the switch the app showed, when the app says (MARKETING only
--                   today); NULL otherwise.
--   method SWITCH:  the user flipped the switch.
--   method BACKFILL: the setting as it already stood when this record began -- recorded_at is when
--                   this migration ran, not when the user chose it, which was never stored.
--
-- Kept when the account is deleted, like user_terms_acceptances (V302): the purge anonymises the
-- users row and deletes notification_preferences, but these rows stay as the evidence of what the
-- user agreed to. They hold no personal data of their own. No IP address is stored.
CREATE TABLE notification_preference_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category VARCHAR(32) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    client VARCHAR(16) NOT NULL,
    notice_version VARCHAR(32),
    method VARCHAR(16) NOT NULL,
    CONSTRAINT chk_notification_preference_history_client
        CHECK (client IN ('WEB', 'MOBILE_IOS', 'MOBILE_ANDROID', 'UNKNOWN')),
    CONSTRAINT chk_notification_preference_history_method CHECK (method IN ('SWITCH', 'BACKFILL'))
);

CREATE INDEX idx_notification_preference_history_user
    ON notification_preference_history (user_id, recorded_at);

-- Start from what is stored now: one BACKFILL row per existing choice. Nothing is invented -- each
-- row says only "this was the setting when the record began".
INSERT INTO notification_preference_history (user_id, category, channel, enabled, recorded_at, client, method)
SELECT user_id, category, channel, enabled, now(), 'UNKNOWN', 'BACKFILL'
FROM notification_preferences;

COMMENT ON TABLE notification_preference_history IS
    'Append-only: every change to a notification switch (SWITCH), plus the settings as they stood when '
    'the record began (BACKFILL). The evidence for "Offers and news" consent. Kept after account deletion.';
