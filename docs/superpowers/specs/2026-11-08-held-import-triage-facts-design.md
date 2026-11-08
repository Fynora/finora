# Held imports: say whether the file looked like a statement

Status: proposal, awaiting a decision from Sid. No code in this change.

## Why

A held `IMPORT_NO_TRANSACTIONS_FOUND` import can mean two different things: the file is not a bank
statement, or it is one the reader could not read. The reader already knows which. `ExtractionCheck`
puts `recoveredLines`, `transactionShapedLines` and `looksLikeAStatement` into the exception's
`details`, and `ImportJobWorker.carriesRecoveredEvidence` uses `looksLikeAStatement` to decide whether
to hold the job at all. A person looking at the held item cannot see these facts.

On 2026-11-08 this cost a long investigation. The held item showed "no transactions found" and a
recovered-line count. That did not say that the reader had judged the file a statement, so it was unclear
whether the held queue and the parse result contradicted each other.

## What was checked in the code (read, not run)

- `ExtractionCheck` lines ~151-160 build the three details.
- `ImportJobWorker` line ~593 reads `looksLikeAStatement` from `api.getDetails()`. Its own comment
  says `recoveredLines` is non-zero for any document with text, so the two values that mean something
  are `transactionShapedLines` and `looksLikeAStatement`; `recoveredLines` is context only.
- `ImportJobWorker.describe(e)` returns the exception's class name plus message. This is the text kept
  for a failed job. I found no place that stores the `details` map itself.
- `HeldImportDto.Detail` carries `lastError`; the row form withholds it on purpose.

## Not established

- That `describe(e)` is what ends up in `last_error` for a held job on every path. Only the call site
  was read.
- Whether the held-item alert email includes `lastError`.
- Whether `details` or the exception message is returned to the customer by `GlobalExceptionHandler`
  (line ~151 merges details into a response). If it is, details must not be changed in place.

## Options

**A. Add facts to `describe(e)` for held jobs (recommended).** When the exception is an
`ApiException` with details, append a fixed allow-list of keys to the stored text:
`recoveredLines`, `transactionShapedLines`, `looksLikeAStatement`. Numbers and a boolean only, never
text from the statement. No migration, no customer response change, shown wherever `lastError` is.

**B. Persist the details as JSON in a new column.** Complete and queryable, but a migration, a
backfill decision and a new admin field. Check the Flyway versions on `origin/main` first.

**C. Change the exception message.** Rejected: the message can reach the customer.

**D. Leave as is.**

## Recommendation

A. If the alert email does not show `lastError`, add the same three values to the email as a second
change, decided after reading how the email is built.

## Required verification

1. A unit test: a held job's stored error contains the three facts when the exception carries them and
   nothing else from `details`.
2. A test that the text contains only the allow-listed keys, even when `details` has others.
3. A test that the customer-facing response is unchanged.
4. The admin held-import detail shown on a phone with a real held item, checked by a person.

## Out of scope

The trace page for failures before staging (it shows no analysis row), SMS, and the daily digest.

## Implementation notes

Option A was implemented in `ImportJobWorker.describe` only. Confirmed by reading the code (not run):

- `describe(cause)` is what `recordFailure` stores as `lastError` (`ImportJob.recordFailure` assigns it),
  and `HeldItemAdminAlertService.alertImportHeld` prints `getLastError()` as the email's "Reason". So
  the counts reach the email and the admin held-import detail with no change to either.
- `ApiException.getDetails()` is never null (an empty map when none is given).
- The customer-facing response is built by `GlobalExceptionHandler` from the exception, not from
  `lastError`, and is untouched.

The stored text is now, for example, `ApiException: <message> [recoveredLines=N,
transactionShapedLines=N, looksLikeAStatement=true]`. Only those three keys are read, and only
integer, long or boolean values; anything else is left out. Three worker tests pin this.

Still open, not done here:

- The held-import email links to the queue (`/held-imports`), not to the job. The admin portal has no
  per-job route in `App.tsx`, so a deep link needs a portal change.
- The first held-import email has no Claude-fix line. The overdue email has one (`claudeFixLine`). The
  fix starts after the hold is committed, so its status may not exist yet when the first email is sent;
  not looked into.
- A summary of the columns the reader found in the header (names only) would have pointed straight at
  the 2026-11-08 cause. Not added: it needs a decision on whether header text counts as "from the
  statement" for this email.
- Not run: the worker tests (no Maven here); CI runs them.
