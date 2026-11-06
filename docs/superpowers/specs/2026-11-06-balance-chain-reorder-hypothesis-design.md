# Say whether a balance-chain warning is a same-day reorder

Date: 2026-11-06. Status: draft for Sid's sign-off. No code is written against this yet.

## Why

On 2026-11-06 a real customer's PDF (layout `FP-1-095C8AA0`, analysis `SA-20261106-0211`, 1692 rows)
staged with `BALANCE_CHAIN` = WARNING. The admin trace showed `discrepanciesCount=3`, `gapsCancel`, and
these shapes, all on rows that sit on the same day as their neighbours:

| Row | What the trace said |
|---|---|
| 257 | gap equals the amount on row 258 |
| 258 | gap matches no amount in the rows |
| 259 | gap equals the amount on row 257 |

Nothing in the trace could say which cause that was. Deciding took a person reading the shapes, and the
only way to confirm was the customer's statement, which staff may open only for **held** imports. This job
was staged, not held, so there was no route to it (`AdminHeldImportService.contentForOriginalView` calls
`requireHeld`).

The shapes are not arbitrary. Write the true order of two adjacent same-day rows as `Y, X`, each printed
with its own balance after it, from a balance `B`: `B+y` after Y, `B+y+x` after X. Suppose the reader
staged them as `X, Y`, followed by `Z`. The walk (`BalanceChainValidator.walk`) then reports:

- X: expected `B+x`, printed `B+y+x`, gap `y`. This is "gap equals the amount of the next row".
- Y: expected `B+y+x+y`, printed `B+y`, gap `-(x+y)`. This matches no single amount.
- Z: expected `B+y+z`, printed `B+y+x+z`, gap `x`. This is "gap equals the amount of the row two back".
- The gaps sum to zero.

That is exactly the observed signature. A balance column that slipped, a direction flipped or an amount
misread each give different shapes (for example swapped balances give a gap of `y-x` on the first row,
which matches neither amount). So the signature identifies a reorder, but only by hand and only by
someone who has worked it out. This is a derivation from the code's arithmetic. It was **not** measured
on the customer's values, and the cause for that statement is still **not established**.

## Goals

1. When `BALANCE_CHAIN` is WARNING or FAILED, test, on the staged rows, whether re-ordering a small run of
   consecutive same-day rows makes the chain reconcile. Report the answer as positions and yes/no only.
2. Show that answer in the admin trace and, on first flag, in the layout-review email (the email already
   carries a "What the check found" line since #2481).
3. Make no change to staged rows, the verdict, the discrepancy list or what the customer sees.

## Non-goals

- **Re-ordering rows in the ledger.** Report only. Changing the order of financial rows needs its own
  spec and value-level proof on the corpus (`CLAUDE.md`, "No guessing").
- **Downgrading WARNING.** A reorder that reconciles is still reported as WARNING in v1.
- **Opening customer statements or widening who may.** No access change.
- **Severity tiers, digest email, mobile layout.** Separate specs.
- **Diagnosing causes other than a reorder.** Misread amounts and dropped rows stay as the shapes
  describe them today.

## Anti-goals (these would count as failure even if the feature technically works)

- Any amount, balance, date or narration reaching the finding, the trace or the email. Only row positions
  and booleans may.
- A reorder reported for a statement where it is a coincidence. The test must demand exact reconciliation
  of every row in the run and of the row after it, never "close".
- Positions that point at the wrong row. `Discrepancy.rowIndex` was already wrong once on newest-first
  statements (see its Javadoc); this must use displayed positions, as `report(rows, opening, displayedPositionOf)` does.
- A slower upload. The test must be bounded however many rows a statement has.
- Silently changing an existing finding's shape. New keys only, behind the allowlists.

## Design

### Where it runs

At the end of `BalanceChainValidator.report(...)`, after `walk`, only when `result.status()` is WARNING or
FAILED and `discrepancies` is non-empty. It adds one key to the finding's `details`:

```
reorderHypothesis: {
  tested:        true | false,
  reason:        "NOT_TESTED_TOO_MANY_ROWS" | "NOT_TESTED_DIFFERENT_DAYS" | "NOT_TESTED_FAILED_VERDICT" | null,
  windows: [ { firstRow: 257, lastRow: 258, reconciles: true, orderings: 1,
               order: [1, 0] } ]          // positions within the window, 0-based
}
```

`firstRow` and `lastRow` are displayed positions. `order` says: shown row `firstRow + order[i]` goes in
slot `i`. No other value is carried.

### Choosing windows

A window is a maximal run of consecutive displayed positions whose rows are discrepant. In the 257-259
case the run is 257-259 even though only 257 and 258 were transposed, because row 259 is discrepant as a
consequence (the walk carried the wrong balance into it). The search tries every ordering of the whole
run, so it finds the order that puts 257 and 258 right and leaves 259 where it was. A transposed pair
whose first row happens to reconcile by coincidence is not covered; that is accepted for v1.

Limits, all constants in the validator beside `MAX_DISCREPANCY_SHAPES`:

- At most **6 rows per window** (720 orderings). A longer run is `NOT_TESTED_TOO_MANY_ROWS`.
- At most **3 windows** per finding.
- Every row in a window must share one date. Otherwise `NOT_TESTED_DIFFERENT_DAYS`. A reorder across
  days is not something the validator's own notes describe, and requiring one day keeps coincidence
  matches out.
- Every row in the window needs an amount and a printed balance. A row without either makes the window
  not testable.

### The test

For each ordering of the window's rows (each row keeps its own printed balance, which is how the
statement prints it):

1. Start from the printed balance last seen before the window (the walk already records this as
   `printedBefore`; the opening balance if the window starts the statement and one was detected).
2. For each row in the new order: `running + signedAmount(row)` must equal that row's printed balance,
   exactly (`compareTo == 0`). Then `running = printed balance`.
3. The row **after** the window must still reconcile from the last row of the new order.

An ordering that passes all three **reconciles**. `orderings` counts how many do. If it is exactly **1**,
`order` is reported. If it is more than 1 (equal amounts make several orders valid), `reconciles` is true,
`orderings` says how many, and `order` is omitted: the validator does not pick one.

### What is persisted and shown

- `ImportVerificationRecorder` rebuilds details from an allowlist (see its `FLAG_KEYS` and
  `DISCREPANCY_SHAPES_KEY` handling). `reorderHypothesis` is added as a third keyed structure with its own
  rebuild: ints and booleans only, the reason from a fixed set, anything else dropped.
- `BalanceMismatchList.tsx` (`BALANCE_MISMATCH_KEYS`) renders one line: "Rows 257-258 reconcile if their
  order is swapped."
- `LayoutReviewService.findingSummaries` appends the same clause to the email line, from the in-memory
  finding, using positions and booleans only.

### Cost

One window is at most 720 orderings of at most 6 rows, each a handful of additions. Three windows is about
13,000 additions per upload, run only on a warning. The walk over all rows is already linear; this adds no
pass over them.

## Test plan

Synthetic statements only, built in the tests. None of this needs a customer's file.

1. Two adjacent same-day rows transposed, each with its own balance: `reconciles`, `orderings: 1`,
   `order: [1, 0]`.
2. The exact 257-259 shape from the trace, rebuilt synthetically: the same result for the 257-258 pair.
3. Three-row rotation: `order` found.
4. **Balances swapped between rows, amounts in place:** not a reorder, `reconciles: false`.
5. A single misread amount: `reconciles: false`.
6. A dropped row: `reconciles: false`.
7. Rows on different days: `NOT_TESTED_DIFFERENT_DAYS`, no windows tested.
8. A run of 7 consecutive discrepancies: `NOT_TESTED_TOO_MANY_ROWS`.
9. Equal amounts so that two orderings reconcile: `orderings: 2`, no `order`.
10. FAILED verdict (a whole column misread): not tested, `NOT_TESTED_FAILED_VERDICT`.
11. **Newest-first statement** (the walk reverses): positions are displayed positions, checked against a
    hand-numbered list.
12. **PDF path** (checked in document order, shown date-sorted): the same.
13. A window at the very start (opening balance present, then absent) and at the very end of the statement.
14. Multi-section statement: findings per section, no cross-section window.
15. **Nothing but positions and booleans leaves:** a test seeds amounts and balances recognisable by value
    and asserts none appears in the finding, the recorder's output or the email HTML.
16. The verdict, the discrepancy list and every existing finding key are byte-identical with and without
    this change on the existing validator tests.
17. 1700 rows with one window: completes in a bounded time.

## Verification, beyond the tests

`CLAUDE.md` says a green suite is not evidence here. So:

- Run the private-traces suite against the branch (`traces.yml`, or `run-against.sh <branch>`) and show
  that **every golden output is unchanged**. This feature adds a field and must not change any row.
- After deploy, wait for a real flagged warning and compare `reorderHypothesis` with the shapes a person
  reads. Until that happens the claim that it identifies real reorders is **not verified on production data**.
- The customer's statement `SA-20261106-0211` is **not** used. The hypothesis cannot be run on it, because
  the rows are not kept. It is the motivating case, not a test.

## Risks

- **A coincidental match.** Exact decimal equality across every row of the window and the row after makes
  it unlikely, and the same-day rule narrows it. It is still a claim about a customer's statement, so the
  wording must say "reconcile if", not "were out of order".
- **A reorder the reader should have caught.** This reports it but does not fix it. A future spec decides
  whether the reader should sort same-day rows by printed balance. That is a change to the staged rows and
  needs the corpus proof above.
- **Recorder drift.** A new keyed structure is one more place an unreviewed field could pass. The
  allowlist test (test 15) is the guard.

## Implementation notes (deviations from the design above)

- `rows` and `order` in each window are **displayed positions** (zero-based, same numbering as the
  discrepancies), `rows` sorted ascending and `order` in the sequence the chain needs. The email and
  the trace print them one-based.
- One reason was added: `NOT_TESTED_ROW_INCOMPLETE` (a row in the window, or the row that follows it,
  has no amount or no printed balance, so the arithmetic cannot be done).
- When more than three runs disagree, the first three are tested and `moreWindowsNotTested: true` is set.
- The test follows the run: the first balance-bearing row after it must still hold from where a trial
  ordering leaves the balance, otherwise that ordering does not count.

## Open questions for Sid

1. Window cap of 6 rows and 3 windows: enough, or should it be lower?
2. Same-day required: agreed, or test across days too?
3. Should an unambiguous reorder ever downgrade WARNING in the email (for example to "likely reorder")? The
   spec says no in v1.
4. Should the hypothesis also run when the verdict is FAILED? The spec says no: a failure is a column
   misread, which a permutation of six rows cannot explain.
