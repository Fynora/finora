-- AI sorting after an import: a stopped run can be tried again, and a finished run says what is left
-- (AI sort break-test, 2026-11-16; spec docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md).
--
-- attempt: which try this is. A run that stopped (the AI could not be reached, a restart, the daily
-- cap) is tried again on the same row, since (user_id, imports_key) is unique; every write from the
-- worker names its attempt, so a worker that was cut off cannot write over the next try.
-- payments_left: this import's payments still waiting for the customer when the run finished --
-- the ones the AI could not place, not the ones the customer answered themselves meanwhile. NULL for
-- runs finished before this column existed.
ALTER TABLE ai_sort_runs ADD COLUMN attempt INT NOT NULL DEFAULT 1 CHECK (attempt >= 1);
ALTER TABLE ai_sort_runs ADD COLUMN payments_left INT CHECK (payments_left >= 0);
