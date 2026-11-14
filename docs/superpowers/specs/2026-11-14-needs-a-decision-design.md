# Needs a decision: one list of every open hold

**Status:** approved by Sid, 2026-11-14 ("1, 2 and 5, go ahead").

## Why

Every held statement carries a promise to its customer: an answer within 48 hours. Today the holds
waiting on us are split across three lists on two pages near the bottom of a 41-link sidebar: Held
Imports (we could not read it), Held Statements (the numbers disagreed), and that page's "holds
without a review record" section. Nothing shows them in the order the promise runs out, or says
which ones somebody, or Claude, is already handling.

## Goals

1. One page, **Needs a decision**, first in the sidebar, listing every open hold of both kinds.
2. Each row says: the kind, the bank, when it was held, the time left on the 48-hour promise
   ("6 h left", or "3 h past the promise" in red).
3. **Who's on it** (Sid's idea 1): an admin's name, Claude, or nobody. Rows nobody is on come first.
4. **Next step** (idea 2): one line saying what to do, worked out from data we already keep.
5. **Claude status** (idea 5): what the Claude fix did for that hold, as the hold's own page shows it.
6. Opening a row goes to the hold's existing page, where every decision is made today.
7. The dashboard's and the bell's hold items open this page.

## Non-goals

- Deciding on this page. Approve, reject, reprocess and resolve stay on the existing pages
  (Sid: the other way rebuilds two decision screens, including the audited statement opening).
- "I'm on it", same-cause groups, "Let Claude try", Claude's monthly count (ideas 3, 4, 6, 7: a later PR).
- Removing the old pages: they keep history, filters and the resolved tab.

## Anti-goals

- Customer content in the list. It shows no statement text, file name, error text or user id
  (less than the two lists today, which show user ids and file names), so `AdminReadAuditPolicy`
  classifies it as carrying no personal data.
- A hold shown twice. A re-upload that an open review already covers is decided by that review and
  is not listed on its own (the same rule `HoldOverdueEscalationService` uses).
- A next step that tells someone to do something the system will refuse.

## Approaches

1. **Front door (chosen):** one read-only list built by the backend, rows link to the existing pages.
2. Decide on the new page: rebuilds both decision flows. Rejected.

## Design

### Which holds

Every import job with status `HELD_FOR_REVIEW` or `HELD_FOR_TRUST_REVIEW`, minus trust holds with
no review record that an open review covers. The clock is `import_jobs.finished_at`, set on every
entry into a hold; the promise is `ImportJob.HOLD_PROMISE` (48 h), the same start and length the
overdue email uses (`ImportJob.isHoldOverdue`).

Kinds:
- `IMPORT`: held import (`HELD_FOR_REVIEW`); opens `/held-imports?job=<id>`.
- `STATEMENT`: trust hold with a review record; opens `/held-statements/<heldId>`.
- `NO_RECORD`: trust hold with no record and no covering review; opens `/held-statements` (where
  its "Open a review" button is).

An admin sees `IMPORT` rows with `IMPORT_TRIAGE_MANAGE`, the other two with `TRUST_REVIEW_MANAGE`.
The endpoint needs either one.

### Who's on it

- `ADMIN` with the name: a held statement with an assigned admin (`assigned_engineer_id` set).
- `CLAUDE`: the Claude fix is working (SENT), re-reading after a deployed fix (REREADING), or is
  switched on and has not recorded its decision yet.
- `NOBODY`: everything else, including a Claude PR waiting to be merged, because that needs a person.

Held imports have no assignment field, so they are Claude or nobody until "I'm on it" (idea 3).

### Next step

One code per row, worked out on the server; the page owns the words. First match wins:

| Situation | Next step |
|---|---|
| NO_RECORD | Open a review for it |
| Statement READY_FOR_IMPORT | The re-run passed: approve it |
| Claude PR_OPEN | Review and merge PR #n |
| Claude SENT | Claude is working on a fix (attempt n of 2, `ClaudeFixJob.MAX_ATTEMPTS`) |
| Claude REREADING | Fix is live: Claude is re-reading it |
| Claude switched on, no decision yet | Claude has not started yet |
| Statement with an assigned admin | <name> is looking into it |
| IMPORT, anything else | Fix the reader and reprocess, or resolve with a message |
| STATEMENT, anything else | Check the evidence, then approve or reject |

### Claude status

The same `ClaudeFixStatus` the hold pages and the overdue email use (`ClaudeFixStatusLookup`), read
for all rows at once, shown in the same words as the existing `ClaudeFixLine`.

### Order

Rows nobody is on first; within each group, least time left first (overdue at the very top); ties
by job id.

### Page

Each row: kind tag, bank (statement holds; held imports show their file type), held since, time
left, who's on it, next step, Claude status, and an "Open" link. Works at 375 px. An empty list says
"Nothing is waiting on a decision."

## Left to the builder

- Endpoint path and DTO names; batching the Claude and admin-name reads.
- A bound on rows returned (the queue is small: 0 open holds in production on 2026-11-14).
