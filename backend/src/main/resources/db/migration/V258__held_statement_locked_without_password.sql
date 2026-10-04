-- A held statement whose file is password-protected and whose password was never kept.
--
-- The synchronous stage endpoint (POST /api/v1/import/pdf/stage) opens a locked PDF with the
-- password the user typed and keeps nothing. When the trust check holds such an upload, the stored
-- file is still locked: a reviewer cannot open it and the parser cannot re-read it. The review is
-- made from the staged rows and the verification findings instead. Recorded once, when the hold is
-- opened, so the admin portal can say so before anyone presses Download or Re-run.
--
-- Queue uploads of a locked PDF carry a password the user agreed to keep (V240), so their holds
-- are FALSE here.
ALTER TABLE held_statements
    ADD COLUMN locked_without_password BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN held_statements.locked_without_password IS
    'TRUE when the held file is a password-protected PDF and no password was kept with it: the '
    'document cannot be opened for review or re-read by the parser. Set once, when the hold is '
    'opened.';
