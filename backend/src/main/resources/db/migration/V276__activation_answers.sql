-- Gate 2a spec §4: "What stopped you?". One row per answer a user taps -- or SKIPPED, kept only so
-- the limits can count it -- at one of three moments:
--
--   NO_FILE        they opened the import screen and never chose a file (asked on the dashboard)
--   REFUSED        a statement was refused or failed to read (asked on the import screen)
--   NOT_CONFIRMED  they cancelled a review, or a read expired unconfirmed
--
-- Answers come from fixed lists (ActivationAnswer); there is no free text anywhere, because a typed
-- answer could carry amounts, names or account numbers. The date only, never the time, as the
-- privacy policy promises -- so no created_at, the same reasoning as V249 and V275. answer_date is
-- the calendar day in app.platform.reporting-zone.
--
-- One answer or skip per user per day, of any moment (spec §4.3), held by the UNIQUE constraint so
-- two answers sent at the same moment cannot both land; ActivationQuestionService turns the second
-- into a 409. The 30-day rule per moment is enforced by the service.
--
-- ON DELETE CASCADE, but AccountPurgeSweepService deletes these rows explicitly: it anonymizes the
-- users row rather than deleting it, so the cascade never fires on that path.
CREATE TABLE activation_answers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    moment VARCHAR(16) NOT NULL
        CONSTRAINT activation_answers_moment_valid
        CHECK (moment IN ('NO_FILE', 'REFUSED', 'NOT_CONFIRMED')),
    answer VARCHAR(32) NOT NULL
        CONSTRAINT activation_answers_answer_valid
        CHECK (answer IN (
            'NO_STATEMENT', 'NOT_COMFORTABLE', 'DIDNT_KNOW_HOW', 'NO_POINT_YET', 'NOT_NOW',
            'TRY_ANOTHER_FILE', 'NO_OTHER_STATEMENT', 'DONT_UNDERSTAND_FAILURE', 'LEAVE_FOR_NOW',
            'ROWS_LOOKED_WRONG', 'WRONG_FILE', 'NOT_READY_TO_SAVE', 'COME_BACK_LATER',
            'SKIPPED')),
    answer_date DATE NOT NULL,
    CONSTRAINT activation_answers_one_per_day UNIQUE (user_id, answer_date)
);
-- The UNIQUE index leads with user_id, which covers the limits' per-user lookups and the purge delete.
-- This one is for the activation page's date-range counts.
CREATE INDEX idx_activation_answers_answer_date ON activation_answers(answer_date);
