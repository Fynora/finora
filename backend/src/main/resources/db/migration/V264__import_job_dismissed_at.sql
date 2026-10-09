-- When the owner dismissed a finished import job from their "Recent imports" list.
--
-- That list (web Statement History, mobile Statements) shows every queued import that did not
-- complete, and a failed one now says why. A failure stays there until twenty newer uploads push it
-- out -- including after the user has re-uploaded the statement and imported it -- still reading
-- "Couldn't finish ... Nothing was added to your accounts". Dismissing hides it from that list on
-- every device. Only FAILED and CANCELLED jobs can be dismissed: a running or held job is not over.
--
-- The job itself is untouched: the row, its failure and its stored object stay exactly as they
-- were for support, the admin queue, retention and the user's data export. A rejected trust review
-- that an operator reopens clears this again, since the import is live once more.
ALTER TABLE import_jobs ADD COLUMN dismissed_at TIMESTAMPTZ;

COMMENT ON COLUMN import_jobs.dismissed_at IS
    'When the owner dismissed this FAILED or CANCELLED job from their recent-imports list. NULL while '
    'it is still listed. Display state only: nothing else reads it. Cleared if a rejected trust '
    'review is reopened.';
