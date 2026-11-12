-- "Know someone who'd want this?": the invite-a-friend card shown after a successful statement import
-- (web Import summary, mobile Import done screen), once per person. One row per user, so web and
-- mobile agree on whether it has been shown:
--
--   times_shown    how many times the card has been shown (at most 2: the next import, and one more
--                  import at least 180 days after a "Not now" -- InvitePromptService has the rules)
--   last_shown_on  the day it was last shown
--   shared_on      the day Share was first tapped; once set, the card is never shown again
--   dismissed_on   the day "Not now" was last tapped
--
-- Days, not timestamps, for the same reason as import_funnel_days (V275): the privacy policy promises
-- the date only for records like this one. Days are in app.platform.reporting-zone (default
-- Asia/Kolkata), like the activation records.
--
-- Nothing about the friend is stored: whether Share led to a message, and to whom, is never known.
--
-- ON DELETE CASCADE, but AccountPurgeSweepService deletes the row explicitly: it anonymizes the users
-- row rather than deleting it, so the cascade never fires on that path.
CREATE TABLE invite_prompts (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    times_shown SMALLINT NOT NULL DEFAULT 0
        CONSTRAINT invite_prompts_times_shown_valid CHECK (times_shown BETWEEN 0 AND 2),
    last_shown_on DATE,
    shared_on DATE,
    dismissed_on DATE
);
