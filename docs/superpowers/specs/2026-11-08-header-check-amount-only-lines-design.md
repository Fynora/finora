# Header check: rows with no date can never count as explained

Status: proposal, awaiting a decision from Sid. No code in this change.

## Why

A real customer statement (layout `FP-1-60BDD09DF`, analysis `SA-20261108-0212`, held job
`08e83192-1480-4cdb-8f91-83d55bea949c`) is held with `IMPORT_NO_TRANSACTIONS_FOUND`. The reader finds
107 rows but none has a debit, credit or balance, so nothing is a transaction.

The statement prints its header on three stacked lines and each transaction on two:

- header line 1 (y 284.1): "Date(Value", "Ref No.", "Transaction"
- header line 2 (y 287.33): "Particulars", "Debit(", "Rs)", "Credit(Rs)", "Balance(Rs)"
- header line 3 (y 292.8): "Date)", "/Cheque No", "Type"
- transaction line a: the date alone (y 311.03)
- transaction line b: narration, reference, type and the amounts (y 315.0)

## What was measured

All of this ran the real `PdfTableLocator` on the blanked trace (the held item's blanked copy), with
four classes stubbed so it compiles without Maven: `ImportDto`, `ErrorCode`/`ApiException`, opencsv's
`CSVReader`, `TransactionNormalizer.recognizedColumnNames()` and
`MerchantIdentityLookup.knownEntityTerms()`. None of them is on the header or column path.

1. The accepted header line is line 3. `recoverMissingDescriptionColumn` adds "Particulars" from line 2
   and nothing else. Final columns: `Date)`, `Particulars`, `/Cheque No`, `Type`. No amount column.
2. `headerQualityWeak` sampled 10 rows, `rowsCleanlyExplainedBy` returned 5, and the rule
   `explained * 2 < sample.size()` is `10 < 10`, false. `reconstructHeader` never ran.
3. Forcing `reconstructHeader` to run, it composed a 7-column header (`Date)`, `Particulars`,
   `/Cheque No`, `Type`, `Debit( Rs)`, `Credit(Rs)`, `Balance(Rs)`) and then declined: `before=5`,
   `after=5`.
4. Per-row verdicts explain the tie. `rowsCleanlyExplainedBy` counts a row only if it has a parseable
   date in the date column and no collision. The three amount-only lines in the sample have no date.
   Under the old header they collide (5 raw cells, 2 bucketed). Under the 7-column header they are
   clean (5 raw, 5 bucketed). They are counted as not explained in both cases.
5. Forcing the 7-column header through to row output gave 106 transactions plus one footer row. Every
   row had exactly one of debit or credit, and balance was present. Checked row against the row
   below it, 105 of 105 balance steps agree to the paisa. The newest balance (17,982.83) equals the
   balance printed in the statement's footer. Credits sum to 1,90,448.00, equal to the footer. Debits
   sum to 1,81,406.81 against a footer of 1,81,406.80.

## Not established

- The one-paisa difference in total debits. Every row's own amount agrees with the balance chain, and
  the oldest row's debit reads 500.00 in the raw text, so it is not a misread in this reader. It may be
  how the statement itself rounds its total.
- Whether the downstream stages accept the forced result. The column names come out as `Date)`,
  `/Cheque No` and `Debit( Rs)`. Only the locator was run, not `TransactionNormalizer`, so it is not
  known that the normalizer recognises these as the date, reference and debit columns.
- The footer row appears as a row with the footer text in the date column and the totals in the debit
  and credit columns. It does this today too. What the later stages do with it was not run.
- Which of the 32 private traces would change. They were not run for this proposal.
- Whether any real statement besides this one prints a date on its own line.

## Options

**A. Count rescued rows (recommended).** Add a second count to `rowsCleanlyExplainedBy`: rows that
collide under the header being judged. Then:

- `reconstructHeader` adopts a candidate when its explained count is higher, or when the same
  explained count holds, at least one sampled row that collided under the old header is clean under
  the candidate, and no sampled row that was clean has started to collide.
- `headerQualityWeak` also reports weak when any sampled row collides under the current header and
  the reconstruction below would be tried. The reconstruction already refuses anything that is not a
  strict improvement, so trying it more often cannot adopt a worse header by itself.
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
   continuation lines must produce an identical header and rows before and after the change.
5. The "Traces against public code" workflow run against the branch. All 32 existing traces pass, and
   the golden outputs are compared row by row, not by count.
6. The full CI run for the PR (the `full-ci` label plus a push), with the jobs actually running.

## Out of scope

Releasing the held import, the one-paisa debit difference, the footer row handling, and the
"Date(Value"/"Transaction" tier above the header.
