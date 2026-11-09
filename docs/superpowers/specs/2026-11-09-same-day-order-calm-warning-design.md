# Tell the customer calmly when a balance warning is only same-day order

Date: 2026-11-09. Status: approved by Sid on 2026-11-09 ("yes to both": the green top line and the wording below). Implemented in the same pull request.

## Why

On 2026-11-06 a customer staged a 1,692-row savings statement (`SA-20261106-0211`) and never imported
it; the draft expired on 2026-11-08. The only problem the checks found was `BALANCE_CHAIN` = WARNING
on three neighbouring rows of one day. Each gap matched another row's amount, and the gaps summed to
zero: the rows were listed in a different order from the one the bank applied them. Every amount and
every printed balance was right. Why the customer did not import is **not established**.

What the review screen showed for those three rows, on web and phone:

- the "Running balance" check labelled **"needs review"** in warning colour;
- a table of **Expected balance / Statement says / Difference**, the differences in warning colour;
- the three rows marked **"Balance didn't match"** in the list;
- the top line **"Imported with notes"** with an amber alert icon.

The server already knows when this is the whole story. Since the 2026-11-06 spec
(`2026-11-06-balance-chain-reorder-hypothesis-design.md`), `BalanceChainValidator` tries every order of
each short run of disagreeing same-day rows and reports, in `details.reorderHypothesis`, whether one
order makes every row of the run and the row after it reconcile exactly. That spec deliberately changed
nothing the customer sees. This one does.

## Goals

1. When a balance warning is fully explained by same-day order, the customer is told so plainly, in
   neutral colours, with no table of differences.
2. Nothing changes for any other balance warning or failure.
3. Admin, the layout-review email and telemetry keep the full facts.

## Non-goals

- Re-ordering rows in the ledger. The rows are still saved in the order the statement listed them.
- Changing the finding's `outcome`. It stays WARNING, so admin traces, the email and telemetry are
  unchanged.
- Any change for FAILED, or for a warning with any row the reorder does not explain.

## Anti-goals (failure even if it technically works)

- Calming a warning that is not fully explained. One unexplained row, one untested run, or more runs
  than were tested means the full warning, exactly as today.
- Any wording that claims more than was proved. What was proved: listed in the bank's order, every
  balance adds up exactly.

## Design

### Server: one fact, decided once

`BalanceChainValidator` adds `sameDayOrderOnly: true` to the finding's `details` when all of these hold:

- the outcome is WARNING;
- `reorderHypothesis.tested` is true and `moreWindowsNotTested` is absent;
- every window was tested and reconciles;
- every discrepancy's displayed row sits inside one of those windows.

Otherwise the key is absent. Positions and booleans only, as the reorder spec requires. Decided on the
server so web and phone cannot disagree.

`ImportReliabilityStatusDeriver`: a `BALANCE_CHAIN` WARNING carrying `sameDayOrderOnly` no longer counts
as a WARNING on its own. If nothing else is wrong the status is CLEAN, so the top line reads
"Imported successfully" in green. OCR and every other condition still apply as today.

### Web and phone: the "Running balance" row when `sameDayOrderOnly` is true

- Outcome label: **"same-day order"** in muted colour, not "needs review" in warning colour.
- Text, replacing the table:
  > A few transactions from the same day are listed in a different order from the one your bank applied
  > them. In your bank's order every balance adds up, so nothing is missing or wrong.
- The table of expected and printed balances is not shown.
- The rows in the list are marked **"Same-day order"** in muted colour, not "Balance didn't match".

An import staged before this ships has no `sameDayOrderOnly` and shows today's warning.

## Rollout

Backend and web ship on merge. The phone change ships with the next store build (no OTA); until then
the phone still shows the old wording for the check, but the top line already turns green, because it
reads the server's status.

## Verification

- Validator tests: the key is set for a two-row and a three-row same-day reorder, and absent for an
  unexplained row, a different-day run, an untested run, more windows than tested, and FAILED.
- Deriver tests: CLEAN when it is the only warning; REVIEW_RECOMMENDED when OCR or any other warning is
  also present.
- Web and phone panel tests for the calm wording, the hidden table and the row label; the old wording
  when the key is absent.
- The trace that started this is not available: the statement was never held, so no blanked copy exists.
  The validator is tested with synthetic rows of that shape.

## Decisions for Sid

1. The top line goes green ("Imported successfully") when same-day order is the only finding.
   Alternative: keep "Imported with notes".
2. The wording above.
