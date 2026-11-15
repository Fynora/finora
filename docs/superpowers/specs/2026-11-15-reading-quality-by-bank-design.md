# Reading quality by bank — design

Date: 2026-11-15. Size: small change (one endpoint, one admin page). Approved by Sid in conversation:
the ranking number, what counts as a fix, and approach A. Revised the same day after a bug-and-gap
review: Sid chose to add reader failures to the rate and to leave out internal accounts.

## Why

Sid: "Reading quality by bank: per bank, rows read, holds and fixes over time, which shows where to
work next". An admin opens one page and sees which bank's statements the reader handles worst, so the
next reader fix goes there.

## What the page shows

A new admin page, **Reading quality by bank**, next to Import Health and Trust Review Metrics.

- Period switch: last 4 weeks / last 12 weeks (weeks run Monday to Sunday, India time).
- One row per bank, sorted by **held or failed**, highest first:
  - **Statements read**: customer uploads the reader parsed for that bank.
  - **Rows read**: transactions extracted from those statements.
  - **Failed reads**: uploads the reader could not read for a reason of its own (Sid, review).
  - **Holds**: statements held for review that are not already a failed read.
  - **Held or failed**: (holds + failed reads) ÷ (statements read + failed reads). This started out
    as holds ÷ statements read (Sid's first choice). Sid widened it at review.
  - **Fixes**: reader fixes that landed for that bank (definition below).
  - A small weekly trend of held or failed.
- Banks with fewer than 10 statements tried (read plus failed) in the period show "too few to judge"
  instead of a rate and sort below every bank that has a rate.
- One extra line, **Bank not known**: holds and fixes whose upload never had its bank named. Shown as
  counts only, with no rate.
- Internal accounts (`users.internal_account`) and admin-scope accounts are left out, as on the
  Activation page (Sid, review). A deleted customer's upload records stay and count.

No file names, no customer ids, no references, no statement content: bank names and counts only.

## Where each number comes from

All counts are live queries over existing tables. No migration. `ReadingQualityService`'s class doc
is the full statement.

- **Bank** comes from `statement_analysis_sessions.bank_name` for every number. A hold or fix finds
  it through its import job, in this order:
  1. The job's staging session (`import_session_id`): the latest upload record that names a bank.
  2. When that session has no upload record at all: `held_statements.bank_name`, but only if that
     exact name already appears in the upload records. A re-read of a held statement, whether an
     admin's "re-run parser" or the Claude fix's re-read, moves the job to a new session that has no
     record (`ImportJob.replaceHeldSession`). Before this rule, every Claude-fixed statement hold
     fell into "Bank not known". Found at review.
  3. The job's failed read: the latest reader failure of the same customer and file (`content_hash`)
     that names a bank. This is how an import hold finds its bank.

  Otherwise `held_statements.bank_name` is not used. It is `DetectedAccountInfo.suggestedName`,
  possibly filled in from a layout profile, so its text can differ from the upload record's.
- **Statements read / rows read**: upload records with `source = CUSTOMER_IMPORT`, `outcome =
  PARSED` and a bank; count and `SUM(row_count)`; week from `created_at`.
- **Failed reads**: `outcome = FAILED` records with a bank, where the failure is one of the reader's:
  `IMPORT_NO_HEADER_DETECTED`, `IMPORT_NO_TRANSACTIONS_FOUND`, `IMPORT_SCANNED_OCR_REQUIRED`
  (`ExtractionCheck` calls each of these the reader's, not the customer's), or an unexpected
  exception (its `failure_code` is a class name, not an `ErrorCode`). Every other code is left out:
  password, damaged, empty or wrong file, payment-app history, multi-account file, no activity, busy
  server. Counted once per file (`user_id` + `content_hash`), so a worker's retry or a re-upload of
  the same failing file counts once.
- **Holds**: a `held_statements` row, or an import hold (`was_held_for_review`), one per job. Dated
  by `held_statements.created_at`, or the job's `created_at` for an import hold. An import hold
  whose failure names a bank is that bank's failed read, so it is not counted as a hold too.
- **Fixes** (Sid's choice), counted once per hold even when both apply, dated by the earlier:
  - The Claude fix job reached `FIXED` (`claude_fix_jobs.state`, dated `updated_at`, which is final).
  - An engineer recorded a fix reference on a held statement (`held_statements.fix_reference` not
    blank, dated by its latest `FINDINGS_UPDATED` event).
- Layout review resolutions are not counted: their table keeps no date or bank. False-positive
  approvals are not counted (Sid: not a reader fix).

## Approaches considered

- **A. Live counts, new page — chosen.** Simple, no migration, works on all history from the day
  bank names started being recorded (V239). Trade-off: when a customer deletes their account, their
  import jobs and holds are deleted with it, so past weeks' hold and fix counts can go down.
- B. Nightly weekly snapshot table. History never shrinks, but more code and it starts empty.
- C. A bank section on Trust Review Metrics. Least code, but that page is about holds only.

## Known limits (shown on the page)

- Uploads from before bank names were recorded (V239, no backfill) have no bank and are not counted.
- An upload the engine named no bank for is not counted under any bank, even when a layout profile
  later fills one in for the customer.
- A file that failed and was later read counts as two tries, one of them failed.
- Counts can drop slightly when customers delete their accounts.

## Rules it must follow

- Backend: `GET /api/v1/admin/imports/reading-quality`, `PLATFORM_DIAGNOSTICS_VIEW`, classified in
  `AdminReadAuditPolicy` (FG-024).
- Tests: backend integration test with made-up rows for every rule above; portal page test.
