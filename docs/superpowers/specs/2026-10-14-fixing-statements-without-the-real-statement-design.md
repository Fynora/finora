# Fixing statements without the real statement

Status: design, awaiting approval. Decisions below were made with the repository owner on
2026-10-14. Revised the same day after a review against the code (see "Review findings").

## Why

Today, when Fynora cannot read a statement, it is held in a review queue, an admin downloads the
real PDF, and the PDF is handed to Claude, which finds the cause and opens a PR. Every fix therefore
puts a customer's full statement in three places: the admin's laptop, a Claude conversation, and
(as a redacted trace) the public repository. The trace tool's own history records a customer's name
and account number reaching the repository once.

A reading fix does not need the customer's identity. It needs the page layout, the amounts and the
dates. `PdfTraceRedactor` already produces exactly that: every text run keeps its page and position,
amounts and dates are kept whole, and every other token is masked character for character unless it
is on an allowlist of statement furniture (column headers, labels, bank names). It fails closed: an
unknown word is masked.

This design moves that capability onto the server, so that in normal work the real statement never
leaves it.

## Decisions

| Question | Decision |
|---|---|
| Who does the work | The owner now; a support team later. Design for the team. |
| Support staff | Blanked copy only. Never the real statement (consistent with the 2026-09-04 decision that a support role must never receive the document). |
| Real statement | Admin or Super Admin only, with a typed reason, an email to Super Admins, an audit entry, and a line in the customer's import history. |
| Customer consent before opening | Parked until the user base grows. For now the customer is told after the fact. |
| How the copy reaches Claude | Staff download the blanked file (projects 1-4); an automatic job later receives it from the server (project 5). Claude never holds an admin login. |
| Merging a fix | Always a person, checking value-level before/after on real statements, never green tests alone. |
| Where blanked test copies live | A separate private repository. Nothing derived from a customer statement goes to the public repository. |
| Alert channel | Email, using the existing `HeldItemAdminAlertService` pattern, to Super Admins only. No statement content in the email. |

Rejected:
- Claude fetching copies with its own admin login: a new, powerful credential to protect.
- Automatic merge when checks pass: reading changes have produced wrong values with every test green.
- Public repository after a human check: one human mistake is permanent publication.

## What a blanked copy does and does not hide

Measured from `PdfTraceRedactor` and `TraceValidator` as they are today:

| Kept | Hidden |
|---|---|
| Every text run's page, position and width (exact for unmasked runs, rounded to a whole point for masked ones, from redactor version 5) | Names, narration words, anything not on the allowlist: each letter becomes `X`/`x`, each digit `9` |
| Amounts (`1,234.56`) and dates, whole | Any run of 8 or more digits (account, card and reference numbers), even if amount-shaped |
| Allowlisted statement words and bank names | An IFSC's branch code (its 4-letter bank prefix is kept) |
| Punctuation and symbols | |
| **The length and capitalisation of every hidden word** | |

The validator then refuses a copy containing an email address, an Indian mobile number or a full
IFSC.

So a blanked copy is **not anonymous**. Two things in it can help someone who already knows
something about the customer:
- the length and capital letters of hidden words ("Xxxxxx Xxxxxxx" is a 6- and a 7-letter name);
- the sequence of amounts and dates, which anyone holding the real statement could match.

Neither is removable without breaking the copy's purpose: the reading code and the
description-corruption check depend on description length, and the amounts and dates are what a
balance or row bug is about. This is an accepted, documented risk, which is why copies stay private
(project 3) and are never published.

## Before and after

| | Before | After |
|---|---|---|
| What staff download | The real PDF | A blanked copy |
| Where the real statement goes | Laptop, then a Claude conversation | Stays on the server |
| Support staff | Not allowed | Blanked copy only |
| Last-resort access | Download, audit entry only | Browser view only, reason, email, audit, customer notice |
| What Claude sees | The full statement | The blanked copy only |
| When a fix starts | When someone picks it up | Automatically on hold (project 5) |
| Test copies | Public repository | Private repository |
| After a fix is live | Re-run by hand | Re-run automatically, customer told (project 5) |

## Five projects, in order

Each gets its own plan, build and verification.

1. **Blanked copy from the server.** Specified in full below.
2. **Safer access to the real statement.** Replace the two existing download endpoints
   (`AdminHeldImportController` and `AdminHeldStatementController` `/document`) with a browser
   view: required reason, Super Admin email, audit entry, and a customer-visible line in import
   history. The plan must fix the exact wording and moment of the customer line (written when the
   view opens, naming the date and "to fix a reading problem", nothing about who).
3. **Private home for test copies.** Private repository for traces, plus:
   - who has access (the owner; support staff never);
   - how the public repository's trace-backed tests reach it, or whether those tests move to the
     private repository instead (a token to a private repository inside a public repository's CI
     is itself a risk to weigh);
   - **customer deletion:** each private trace records the held job's id, never the user's id or
     name; when a customer deletes their account, the traces from their jobs are removed;
   - secret scanning on the private repository too;
   - what happens to the 32 traces already in the public repository.
4. **Keep the garbage, hide the person.** Redactor changes so broken descriptions stay visible:
   bank wording that identifies nobody (UPI, NEFT, footer and notice sentences) kept, broken
   characters kept as they are, names and references still masked. Plus a row map: which page lines
   went into each staged row, positions only. Done only when a real garbage-description case is
   fixed from the blanked copy alone.
5. **Automatic Claude job.**
   - Starts when a statement is held. One job per held item, keyed on the held job's id, so a
     repeat trigger does nothing. At most 2 attempts.
   - Receives only the blanked copy, and only one marked "matches". A copy marked "does not match"
     or "not checked" goes to a person instead.
   - Its token may only push a branch and open a PR in the repository: no merge, no settings, no
     production, no database, no secrets. Text inside the copy is data, never instructions; with
     narrations masked, very little of it is free text at all.
   - The PR must contain the trace-backed test and the before/after values; a person reviews and
     merges.
   - After a fix is deployed the server re-runs the held statement (`reprocess` is already safe to
     run speculatively) and tells the customer. The existing 48-hour escalation email gains a note
     of what the job found or that it gave up.

## Project 1: blanked copy from the server

### What the person sees

On a held item in the admin portal, in both queues (`HELD_FOR_REVIEW`, and held statements awaiting
trust review), a **Download blanked copy** button. It downloads a `.trace` file named after the held
item's reference, never the customer's file name (file names often carry a name). The page shows
the matching check's result: "Matches the real statement", "Does not match" (with the differing rows)
or "Not checked" (with the reason).

### Who may use it

Anyone holding the queue's own permission (`IMPORT_TRIAGE_MANAGE` or `TRUST_REVIEW_MANAGE`). It is
deliberately **not** pinned to the ADMIN or SUPER_ADMIN role, unlike the real-document endpoints:
the blanked copy is what a support role is allowed. The existing "only items in the queue" rule
applies, so the permission cannot pull any customer's statement by job id.

### What happens on the server

1. Write the audit entry first (`HELD_IMPORT_BLANKED_COPY` / `HELD_STATEMENT_BLANKED_COPY`), before
   any bytes are read, as the existing download does. Its details hold ids and the check's result,
   never statement text.
2. Read the stored bytes through `StatementContentService.read`. For a password-protected PDF with a
   saved password, unlock in memory through the existing `reviewCopy` path. Without a saved
   password, stop with a clear message; nothing is produced.
3. Acquire positioned text once, with the same acquirer the import used (text layer or OCR), so the
   copy is made from exactly what Fynora read, and both sides of the check start from the same runs.
4. **Privacy check.** Redact with `PdfTraceRedactor`, then run `TraceValidator`'s privacy scan
   alone (email, Indian mobile number, full IFSC). A copy it refuses is never returned, whatever
   the matching check would say; the person is told which kinds were found, never the text. The
   validator's other blockers are not used here: its "no table found" blocker would refuse exactly
   the never-seen layouts this exists for. Metadata in the copy is written by the server (redactor
   version, allowlist fingerprint, date, the held item's reference) and never includes the file
   name or any customer text.
5. **Matching check.** Separate from the privacy check: passing it says the copy reads the same,
   not that it is safe. Run the reading pipeline from positioned text twice: on the real runs, and
   on the trace file's own round trip (format, then parse), because the file is what Claude gets.
   The file carries no run's source, height or confidence, so a scanned statement can honestly come
   back "does not match". Both readings use one fresh random user id, so no customer's rules,
   merchants, duplicates or resolutions are read and the result cannot depend on them (global rules
   still apply to both). Compare only what reading produces:
   - number of sections and rows;
   - each row's date, amount, direction and balance;
   - which page lines were grouped into each row's description (by position, not text);
   - the statement period, opening and closing balance;
   - the verification findings' rules and outcomes.

   Not compared: account holder name and account number (they are masked on one side by design),
   categories, merchants and duplicate flags (they come from description words and customer data).
6. **What a mismatch shows.** The differing rows and the names of the differing fields, and the
   hidden words on those rows **in their masked form only**. Never a value: the real word would
   hand support staff the customer's text, and a real "amount" can be a phone number the copy
   masked. Deciding whether a hidden word belongs on the allowlist
   needs the real statement, which is the Admin-only path (project 2).
7. Store nothing. The copy and the comparison exist only for the response.

The real bytes and the unredacted runs never leave the method, and never appear in a log line, an
exception message, an error report or an audit entry from this code path.

### Code changes

- Move `PdfTraceRedactor`, `TraceMetadata`, `TraceValidator` and the format/parse half of
  `PdfTrace` (as `TraceFormat`) from `backend/src/test/.../pdf/fixtures` into main code (a new
  `com.finora.imports.pdf.trace` package), with unchanged behaviour. `PdfTrace`'s
  resource-loading half and `TraceQualityReport` stay in test code; production needs neither. They depend only on main-code classes (`PdfTableLocator`,
  `PositionedText`, `BankRegistry`) and the JDK, so the move needs no test library in production.
  `trace-capture.sh` and every existing test keep working against the moved classes.
- Add a reading entry point that starts from positioned text instead of bytes, sharing everything
  after acquisition with `PdfPreviewGenerator.generateSectionsWithContext`, and accepting the empty
  per-customer indexes. Today the trace tests call `PdfTableLocator.locateAll` directly, which stops
  before staging and verification; the matching check needs the whole pipeline.
- `BlankedCopyService` (steps 1-7) and `BlankedCopyComparison` (the comparison and its result).
- Two endpoints, one per queue, beside the existing `/document` ones.
- A switch, `app.blanked-copy.enabled`, off by default until the owner has run it on a real held
  statement. A status endpoint tells the admin portal whether to show the button.
- Counts, never content, for monitoring: copies produced, refused by the privacy check, and each
  matching result.
- Admin portal: the button and the result, on the held-import view and on Held Statement Detail.

### Out of scope for project 1

- CSV statements. The redactor works on positioned PDF text. A held CSV shows "Blanked copy not
  available for CSV yet".
- Any change to what the redactor keeps (project 4).
- Any change to the real-document download (project 2).

### Errors

| Case | Result |
|---|---|
| Switch off | Endpoint returns not found; button hidden |
| Item not in a held state | Refused, same as the existing download |
| Locked PDF, no saved password | "This statement is password-protected and no password is saved" |
| Storage or decrypt failure | Generic error; the audit entry already exists |
| Privacy check refuses the copy | No file; the blocker kinds are shown (for example "phone number"), never the matched text |
| Matching check differs | File returned, marked "does not match", differing rows listed |
| Matching check fails to run | File returned, marked "not checked", with the reason |

A copy marked "does not match" or "not checked" may still help a person, but a fix must not rest on
it alone: the bug may exist only in the copy. Project 5 never starts on one.

### Tests

- Unit: the comparison detects each kind of difference (row count, amount, date, balance,
  description grouping, period, opening and closing, finding outcome), reports none for identical
  readings, and ignores account name, account number, category, merchant and duplicate fields.
- Synthetic statements built with `PdfFixtureBuilder`: redacting and replaying both sides gives
  "matches".
- A synthetic statement whose reading depends on a masked word gives "does not match", and the
  report contains only the masked form.
- Integration:
  - the permission and held-state gates on both endpoints, and the switch;
  - the audit entry is written before the read, including on a failed read;
  - with a synthetic source carrying a planted name, narration, phone number, email, IFSC, long
    digit run and a name-bearing file name: none of them appears in the response, the file name,
    the audit entry or the captured log output, on success and on each failure path;
  - the replay reads no rule, merchant, duplicate or resolution data for the customer.
- Moved classes: the full existing trace-backed suite passes unchanged, and `trace-capture.sh`
  writes the identical trace for a synthetic PDF before and after the move.

### Done when

- A support-permission account can download a blanked copy of a held PDF and cannot download the
  real one.
- The matching check reports "matches" on the synthetic corpus and catches a planted mismatch.
- Backend `./mvnw verify`, admin lint, type-check and tests pass; corpus run unchanged.
- Run by the owner against a held statement in production with the switch on: the copy is produced
  and the check result is shown, with nothing but the copy leaving the server.

## Review findings (2026-10-14)

An outside review gave only an outline; each topic was then checked against the code. What changed
in this document as a result:

**Must change before building (now in project 1 above):**
1. The mismatch report named "the hidden tokens". Showing the real word would have handed support
   staff customer text. It now shows the masked form and position only.
2. The reading pipeline uses the customer's own rules, merchants and duplicates
   (`PdfPreviewGenerator.buildLedgerSection`). The replay now uses empty ones on both sides, and the
   comparison covers reading output only.
3. The customer's file name, which often carries a name, is kept out of the copy and its download
   name.
4. No statement text in logs, exceptions, error reports or audit details from the new path, with a
   test that captures them.
5. The privacy check and the matching check are separate; a privacy refusal always wins.
6. A copy that does not match, or was not checked, is marked and is never the basis of an
   automatic fix.
7. An off switch, off in production until the owner has verified it.

**Before going live (added to projects 2, 3 and 5):** the customer notice's wording and timing;
deletion of traces when a customer deletes their account; who can reach the private repository and
how CI does; the automatic job's one-per-item rule, retry limit, token scope and PR contents.

**Checked and found already in place:** production hides exception messages from responses
(`GlobalExceptionHandler.handleGeneric`); the existing download is restricted to held items; the
audit entry is written before any bytes are read.

**Can wait:** making audit rows tamper-proof in the database. Today the application never deletes
an audit row; its only edit is the retention pass (`AuditService`, `app.audit.redaction.retention-days`,
730 days by default, never under 90), which replaces an old row's details with a marker and keeps
who, what and when. So the reason typed for opening a real statement is kept for two years. Also
can wait: CSV; consent before opening.

**Not applicable:** tenant isolation; Fynora's admin portal is a single tenant.

## Risks that remain

1. The redactor can miss something. It fails closed and validates, and project 3 keeps copies
   private, but no redactor is perfect.
2. A blanked copy is not anonymous (see "What a blanked copy does and does not hide").
3. Amounts and dates stay visible to Claude, and so to Anthropic. The privacy policy should be
   checked for mention of an AI processor; this is not a legal judgement.
4. A browser view (project 2) prevents downloads, not screenshots. The reason, the email and the
   customer notice make access visible, not impossible.
5. Email alerts depend on email delivery. The audit log is the record of truth.

## Open questions

- CSV statements: needed, or rare enough to leave?
- The 32 traces already in the public repository: leave them, or move them to the private
  repository and rewrite history (which needs its own explicit decision)?
- Privacy policy wording for AI-assisted fixing.
