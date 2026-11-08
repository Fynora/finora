# Header check: rows with no date can never count as explained

Status: proposal, awaiting a decision from Sid. No code in this change.

## Why

A real customer statement (held in the admin queue on 2026-11-08; the reference is deliberately not
written here) is held with `IMPORT_NO_TRANSACTIONS_FOUND`. The reader finds 107 rows but none has a
debit, credit or balance, so nothing is a transaction.

This document holds no value from that statement: no amount, date, balance, account detail or
reference. Where a check compared the reader's output with figures printed on the statement, only the
result of the comparison is given.

The statement prints its header on three stacked lines and each transaction on two:

- header line 1 (y 284.1): "Date(Value", "Ref No.", "Transaction"
- header line 2 (y 287.33): "Particulars", "Debit(", "Rs)", "Credit(Rs)", "Balance(Rs)"
- header line 3 (y 292.8): "Date)", "/Cheque No", "Type"
- transaction line a: the date alone (y 311.03)
- transaction line b: narration, reference, type and the amounts (y 315.0)

## What was measured

All of this ran the real `PdfTableLocator` and `TraceFormat` on the held item's blanked copy, with five
stand-ins so it compiles without Maven: a reduced `ImportDto` (three records), a reduced
`ErrorCode`/`ApiException`, an empty opencsv `CSVReader`, `TransactionNormalizer` reduced to
`recognizedColumnNames()` returning an empty set, and `MerchantIdentityLookup` returning no known
entities. By reading the code, none of them is on the header or column path; that was not tested by
running with the real versions. The blanked copy is not the original PDF.

1. The accepted header line is line 3. `recoverMissingDescriptionColumn` adds "Particulars" from line 2
   and nothing else. Final columns: `Date)`, `Particulars`, `/Cheque No`, `Type`. No amount column.
2. `headerQualityWeak`, judging the 4-column header, sampled 10 rows. `rowsCleanlyExplainedBy`
   returned 5, and the rule `explained * 2 < sample.size()` is `10 < 10`, false. `reconstructHeader`
   never ran. (This boundary case is also why the result is fragile: 4 of 10 would have run it.)
3. Forcing `reconstructHeader` to run, it composed a 7-column header (`Date)`, `Particulars`,
   `/Cheque No`, `Type`, `Debit( Rs)`, `Credit(Rs)`, `Balance(Rs)`) and then declined: `before=5`,
   `after=5`. Its `before` is scored against the accepted line alone (3 columns), not the recovered
   4-column header the gate used.
4. Per-row verdicts explain the tie. `rowsCleanlyExplainedBy` counts a row only if it has a parseable
   date in the date column and no collision. The three amount-only lines in the sample have no date.
   Under the 3-column header they collide (5 raw cells, 2 bucketed). Under the 7-column header they
   are clean (5 raw, 5 bucketed). They are counted as not explained in both cases. No sampled row
   went from clean to colliding under the 7-column header; two rows collide under both (the line
   holding the narration and the amounts, and a bracketed value-date line).
5. Forcing the 7-column header through to row output gave 106 transactions plus one footer row. Every
   transaction had exactly one of debit or credit, and balance was present. Each row's balance was
   checked against the row below it using its own debit or credit: 105 of 105 steps agree to the
   paisa. The newest row's balance equals the available balance printed in the statement's footer,
   and the credits sum to the footer's credit total. The debits sum is one paisa above the footer's
   debit total.

## Not established

- The one-paisa difference in total debits. Every row's own amount agrees with the balance chain, and
  the oldest row's debit, which the chain cannot check, reads the same in the raw text as in the
  output, so it is not a misread in this reader. It may be how the statement itself rounds its total.
- Whether the downstream stages accept the forced result. The column names come out as `Date)`,
  `/Cheque No` and `Debit( Rs)`. Only the locator was run, not `TransactionNormalizer`, so it is not
  known that the normalizer recognises these as the date, reference and debit columns.
- The footer row appears as a row with the footer text in the date column and the totals in the debit
  and credit columns. It does this today too. What the later stages do with it was not run.
- Which of the 32 private traces would change. They were not run for this proposal.
- How often the sample contains a colliding row on statements that are read correctly today. Option A
  runs `reconstructHeader` whenever it does, so this decides how much more often that code runs. Not
  measured.
- How a held import is released after a fix ships (a retry by a person, or by the worker). Not looked
  up. Until then the customer has no statements from this layout.
- Whether the original PDF reads the same as the blanked copy.
- Whether any real statement besides this one prints a date on its own line.

## Options

**A. Count rescued rows (recommended).** Add a second count to `rowsCleanlyExplainedBy`: rows that
collide under the header being judged. Then:

- `reconstructHeader` adopts a candidate when its explained count is higher, or when the same
  explained count holds, at least one sampled row that collided under the old header is clean under
  the candidate, and no sampled row that was clean has started to collide.
- `headerQualityWeak` also reports weak when any sampled row collides under the current header, so the
  reconstruction is tried. The reconstruction only adopts a strict improvement under the rule above,
  so trying it more often cannot by itself adopt a worse header. That is by reading the code; it has
  not been run over other statements.
- Cost: `reconstructHeader` runs for more statements (every one with a collision in the sample). It
  runs on a copy and records capabilities only when adopted.

**B. Count undated clean rows as explained.** Smallest edit, but a narration continuation line with
no date also bucketed clean under any header. Counting it raises `explained` for every bank and would
stop `headerQualityWeak` firing where it fires today. Not recommended.

**C. Handle the three-line header in the wrap merge.** Extend `mergeHeaderLines` to take the tier
above regardless of the sample. The code's own comments say the refusal there is deliberate, because
a footer or caption line above a header has been folded in before. Not recommended.

**D. Leave as is.** The statement stays unreadable and the customer's import stays held.

## Recommendation

A, as a change to the reader only. No schema, API or other layout change.

## Required verification before this ships

1. A new synthetic test (three-line header, two-line transactions) built with `PdfFixtureBuilder`,
   expecting debit, credit and balance populated.
2. The blanked copy of this statement added to `Fynora/finora-statement-traces` as a test with a
   golden output and the balance-chain check above.
3. The same statement taken through `TransactionNormalizer` and the staging path, not only the
   locator, with the transaction count and balance verification reported.
4. Negative tests: a statement whose header is fine and whose sampled rows include narration-only
   continuation lines must produce an identical header and rows before and after the change. Also the
   boundary of the old rule (4, 5 and 6 explained of 10), and a document whose header repeats on
   every page.
5. The "Traces against public code" workflow run against the branch. All 32 existing traces pass, and
   the golden outputs are compared row by row, not by count.
6. The full CI run for the PR (the `full-ci` label plus a push), with the jobs actually running.

## Out of scope

Releasing the held import, the one-paisa debit difference, the footer row handling, and the
"Date(Value"/"Transaction" tier above the header.

## Implementation notes

Option A was implemented in `PdfTableLocator` only (no schema, API or other-class change):

- `headerQualityWeak` also reports weak when at least `HEADER_QUALITY_MIN_SAMPLE` (2) sampled rows
  collide under the current header and carry no parseable date (`undatedCollidingRows`).
- `reconstructHeader` still declines when the candidate explains fewer dated rows. On a tie it now
  adopts the candidate when at least 2 sampled rows collide under the accepted line and are clean
  under the candidate, and none that were clean now collide (`collidingRows`).

Measured, with the same stand-ins as above (the real `PdfTableLocator`, no Maven):

- On the held item's blanked copy, run unforced: 7 columns, 106 transactions plus the footer row, one
  of debit or credit on every transaction, 105 of 105 balance steps agree. This equals the forced run.
- Old and new locator run over all 32 traces in `Fynora/finora-statement-traces`: every section's
  rows, auxiliary text and capability list identical, no exceptions. 2,048 rows were compared; two of
  the 32 traces produced no rows in this harness, so they were not exercised by it.
- A synthetic three-line-header fixture fails on the old locator and passes on the new one. The
  rescue works from 2 transactions and does not apply to a table of 1 (deliberate: one colliding
  line is not enough evidence). That limit is pinned by a test.

Not run: the JUnit suite, `TransactionNormalizer` and the staging path, the "Traces against public
code" workflow, and the blanked copy as a 33rd test in the private repository. The new test class
was checked by mirroring its assertions, not by running JUnit.
