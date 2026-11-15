# Reading quality by bank — design

Date: 2026-11-15. Size: small change (one endpoint, one admin page). Approved by Sid in conversation:
the ranking number, what counts as a fix, and approach A.

## Why

Sid: "Reading quality by bank: per bank, rows read, holds and fixes over time, which shows where to
work next". An admin opens one page and sees which bank's statements the reader handles worst, so the
next reader fix goes there.

## What the page shows

A new admin page, **Reading quality by bank**, next to Import Health and Trust Review Metrics.

- Period switch: last 4 weeks / last 12 weeks (weeks run Monday to Sunday, India time).
- One row per bank, sorted by **hold rate**, highest first (Sid's choice):
  - **Statements read** — customer uploads the reader parsed for that bank.
  - **Rows read** — transactions extracted from those statements.
  - **Holds** — those statements held for trust review.
  - **Hold rate** — holds ÷ statements read.
  - **Fixes** — reader fixes that landed for that bank (definition below).
  - A small weekly trend of hold rate.
- Banks with fewer than 10 statements in the period show "too few to judge" instead of a rate and
  sort below every bank that has a rate.
- One extra line, **Bank not known**: import holds (`HELD_FOR_REVIEW`) and fixes on them. The parse
  failed before the bank was named, so the code never records one (`FixJobStarter`: "an import hold
  never learns its bank"). Shown as counts only, no rate.

No file names, no customer ids, no references, no statement content: bank names and counts only.

## Where each number comes from

All counts are live queries over existing tables. No migration.

- **Bank** comes from one place for every number: `statement_analysis_sessions.bank_name`.
  `held_statements.bank_name` is not used, because it is taken from a different field
  (`DetectedAccountInfo.suggestedName`, which can be a generic fallback) than the evidence row
  (`bank().officialName()`), so the two names can differ for the same bank.
- **Statements read / rows read**: `statement_analysis_sessions` rows with `source = CUSTOMER_IMPORT`,
  `outcome = PARSED`, `bank_name` set; count and `SUM(row_count)`; week from `created_at`.
- **Holds**: `held_statements` row → `import_jobs` (`import_job_id`) → the latest evidence row with
  the same `import_session_id` that names a bank → its `bank_name`. Week from
  `held_statements.created_at`. Import holds (`import_jobs.was_held_for_review`) go through the same
  link, dated by the job's `created_at`: normally they have no evidence row and land in "Bank not
  known", but one that was later read under a bank counts for that bank (builder's call, so a later
  fix on it and the hold sit in the same row). A job held both ways is one hold.
- **Fixes** (Sid's choice), counted once per hold even when both apply, dated by the earlier:
  - Claude fix job reached `FIXED` (`claude_fix_jobs.state`, dated `updated_at`).
  - An engineer recorded a fix reference on a held statement (`held_statements.fix_reference` not
    blank, dated by its latest `FINDINGS_UPDATED` event).
  - Bank found through the same job → evidence row link.
- Layout review resolutions are not counted: their table keeps no date or bank. False-positive
  approvals are not counted (Sid: not a reader fix).

## Approaches considered

- **A. Live counts, new page — chosen.** Simple, no migration, works on all history from the day
  bank names started being recorded (V239). Trade-off: when a customer deletes their account, their
  import jobs and holds are deleted with it, so past weeks' hold and fix counts can go down.
- B. Nightly weekly snapshot table. History never shrinks, but more code and it starts empty.
- C. A bank section on Trust Review Metrics. Least code, but that page is about holds only.

## Known limits (shown on the page in one line)

- Uploads from before bank names were recorded (V239, no backfill) have no bank and are not counted.
- Counts can drop slightly when customers delete their accounts.

## Rules it must follow

- Backend: `GET /api/admin/...` with `@PreAuthorize` naming an admin permission (FG-024) and a
  classification in `AdminReadAuditPolicy`.
- Tests: backend integration test with made-up rows covering the bank link, the 10-statement cutoff,
  fix de-duplication and the bank-not-known line; portal page test.

## Left to the builder

Endpoint path and permission (reuse the one Import Health / Trust Review Metrics use), response
shape, nav placement, chart styling.
