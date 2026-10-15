# Row map: which printed lines made each transaction

Status: design, approved in conversation by the repository owner on 2026-10-15; awaiting review of
this document. This is the first half of project 4 in
`2026-10-14-fixing-statements-without-the-real-statement-design.md` ("Keep the garbage, hide the
person"). The owner chose to build the row map first, and to build "show the bank's own wording"
afterwards as its own design and PR, because that half carries the privacy risk.

## Why

When a statement reads badly, the commonest faults are:
- a description that swallowed a page footer or a bank notice;
- two transactions joined into one;
- one transaction split into two.

In a blanked copy every such line is masked (`Xxxxx xxx`). Someone fixing the reader can see that
a description is long, but not which printed lines went into it. That is exactly the question
these faults raise.

The blanked-copy check also says "does not match" for 20 of the owner's 34 local statements
(measured on 2026-10-15, PR #2180). The description is the commonest field involved (14
statements). The check compares descriptions as text after masking, and doesn't know which lines a
description came from.

## What it does

1. **The reader records provenance.** As `PdfTableLocator` builds each transaction row, it records
   the printed lines the row used. A line is identified by its page and its `y` position, exactly
   as the run positions already appear in a trace. A row built from several physical lines (a
   wrapped narration, a continuation line, a merged block) records all of them. The record holds
   no text.
2. **The record never reaches stored data.**
   - It is carried with the row inside the reader under one reserved key.
   - `PdfPreviewGenerator` moves it into a separate list beside the section's rows, before
     normalisation.
   - From there on, nothing that builds a staged row, a session or a stored finding can see it.
3. **The blanked copy carries the row map.** `BlankedCopyService` appends a block to the copy,
   built from the reading of the **real** statement, with rows numbered as shown on screen (the
   display mapping from PR #2118):

   ```
   # rowmap: section 1
   # row 12: p2 y=401.20,412.80,424.40
   ```

   Positions only. Every `y` named is already in the copy's own run lines. The trace reader treats
   these as comment lines, so existing tools that read a trace are unaffected.
4. **The check compares row structure.** `BlankedCopyComparison` gains a field `ROW_LINES`: for
   each row, does the copy's reading use the same lines as the real reading? A difference names
   the section and the row, as every difference already does.
5. **Unusual rows are pointed out.** The admin portal's blanked-copy panel lists, from the row map
   alone:
   - a row whose lines span two pages;
   - a row that used more than 4 lines;
   - a row with a line far from the line before it in the same row: a gap more than 3 times the
     median gap between consecutive printed lines of the table on that page. That is how a
     swallowed footer or notice looks.

     (Corrected while planning, 2026-10-15. The first wording, "a line below every other row's
     lines on that page", would have flagged the last transaction on every page.)

   Each item reads like "Row 12 took 6 printed lines". These are counts and positions, never text.

## What it does not do

- It shows no wording: the second half of project 4 does that.
- It doesn't change how any statement is read. Recording is additive.
- It records lines, not words. Where a description difference comes from how individual words
  were grouped, a line-level map can't settle it. If the measured match count barely moves, a
  word-level map is the next design. That is not promised here.
- CSV statements are out of scope, as for blanked copies generally.

## Safety proof required before merge

- **Reading is unchanged.** Run `scripts/corpus-run.py` over the owner's 34 local statements on
  `main` and on the branch: the outputs must be byte-identical. Run the private trace tests
  (`-Pprivate-traces`) on the branch: same count, all pass.
- **The note never escapes.** A test reads synthetic statements through every staging path (PDF
  preview, async import job, session confirm) and asserts that the reserved key appears in no
  staged row, session JSON, finding detail or log line.
- **Provenance is complete.** Over the 34 local statements, every staged PDF row has a non-empty
  line list. A row with none fails the measurement. The count of such rows is reported, not
  assumed to be zero.
- **Every row-building path is covered.** Each place in `PdfTableLocator` that creates or merges a
  row records lines. Each has a synthetic test that builds a statement shaped for that path and
  checks the recorded lines.

## Measured and reported, not promised

- The blanked-copy match count over the 34 local statements, before and after (14 matches on
  2026-10-15).
- How many rows the panel would flag in each of the three ways above, over the 34 statements.

## Done when

- The row map is in every PDF blanked copy.
- `ROW_LINES` is checked.
- The panel shows unusual rows.
- The safety proof above passes.
- The two measurements are reported in the PR.

## Risks

- **Many row-building paths.** `PdfTableLocator` is large and has several row-building paths
  (column-bucketed rows, headerless bucketing, two-line blocks, same-day rows, leading-narration
  merges). Missing one leaves rows with no lines. The completeness measurement exists to catch
  that.
- **A merge that drops the record.** A row merge that keeps one row's map and drops the other's
  would understate the lines. Every merge path takes the union, and has a test for it.
