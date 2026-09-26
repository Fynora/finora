# Own-account transfers (Plan 3) — design

Status: approved in conversation 2026-09-26, pending written-spec review.
Program: financial flow model (`docs/proposals/financial-flow-model.md`); Plan 1 shipped as PR #1773.

## Goal

Money moved between the user's own accounts is neither income nor spending. Today it is often both
wrong at once: the debit counts as spending, and the credit sits in "not counted as income" (or in
income). Recognise these transfers automatically, with no setup, from data Fynora already stores.

## Why the written outline changed

The earlier Plan 3 outline proposed an `account_identifiers` table and user-declared untracked
accounts. Two facts replace it:

- The Relationship model (`FAMILY`/`FRIEND`/`OWN_ACCOUNT`/`OTHER`) is editable only through the admin
  controller. No user can create a relationship on web or mobile, so relationship-based matching does
  nothing in practice.
- Every account already stores `account_holder_name` (and `account_number_masked`) from statement
  import. The holder name is enough to recognise the user as sender or payee.

Family tagging moves to Plan 2, where it is one of the choices in the unresolved-review list.

## Evidence (real corpus, measured 2026-09-26, one owner at a time)

The corpus holds several people's statements, so it was grouped by account holder before any
cross-statement check; money between two different people must never look like a self-transfer.

- **Genuine own-transfer pairs carry the same 12-digit UPI/IMPS reference on both legs**, in every
  bank shape seen (dash-delimited UPI, slash-delimited `UPIAB`/`UPIAR`, and a Kotak shape where the
  reference is glued to letters). Coincidental equal-amount pairs between unrelated people did not.
- **Own transfers name the owner in the sender or payee slot.** Own transfers out were counted as
  spending (about ₹2.6L across the owners measured); own transfers in sat mostly in "not counted as
  income" (about ₹1.26L named by sender, plus the reference pairs).
- **A free-text name match is wrong.** Salary NEFT credits print the employee's own name as
  beneficiary; a "narration contains the owner's name" rule would have removed over ₹10L of salary
  from income. The one free-text false hit on a debit was a card tax line naming the cardholder.
- Income is affected only by genuine own transfers counted as income today (two rows, ₹65,000).
- The last digits of another own account appear only on rows the two rules below already catch.

## Rules

Both run in `ReconciliationService`, which already runs after every import, create, edit and delete.
The decision is persisted on the row, so every consumer (dashboard, reports, budgets, insights, Fyn
tools) excludes it through the existing transfer exclusion.

### Rule 1 — shared reference (both legs imported)

Added to the existing transfer pairing pass.

- A reference is a run of exactly 12 digits anywhere in the narration, including glued to letters.
  UPI RRNs and IMPS references are 12 digits; phone numbers (10), card numbers (16) and most account
  numbers (11+) are excluded by length.
- A debit and a credit on two different accounts of the user, same amount (existing tolerance),
  opposite direction, inside the existing date window, that share a reference, pass the gate on that
  evidence alone.
- A shared reference ranks above every other candidate for the same row.
- The card-bill guard (a card bill payment never pairs with a leg on another card) still applies.

### Rule 2 — the user is the sender or payee (one leg only)

A new pass after pairing.

**Slot extraction.** The counterparty name is read only from the fixed slot banks print it in, for the
shapes observed in the corpus:

| Rail | Shape (synthetic) | Slot |
|---|---|---|
| UPI, dash | `UPI-ASHA VERMA-asha@okbank-IFSC0000001-111111111111-UPI` | 2nd field |
| UPI, slash | `UPI/CR/111111111111/ASHA VERMA/BANK/...`, `UPI/DR/...` | after the reference |
| UPI, slash | `UPIAB/111111111111/CR/ASHA VERMA/BANK/...`, `UPIAR/.../DR/...` | after CR/DR |
| UPI, tail | `UPI/RRN 111111111111/UPI_ASHA VERMA` | after `UPI_` |
| IMPS | `MOB-IMPS-CR/ASHA VERMA/BANK/...` | after `IMPS-CR/` |
| IMPS, glued | `SentIMPS111111111111ASHA VERMA/IFSC0000001/...` | after the reference |
| NEFT credit | `NEFT CR-IFSC0000001-<remitter>-<beneficiary>-<ref>` | remitter only |

The NEFT beneficiary slot is never read: it is the account holder on every NEFT credit, including salary.

**Owner names.** The normalised holder names of all the user's accounts, titles (`MR`, `MRS`, `MS`,
`DR`, `SHRI`, `SMT`) dropped. All variants count: banks spell the same person differently.

**Match.** The slot holds at least two name words; its first word equals an owner name's first word;
each later slot word is a prefix of the owner name's next words, in order. Accepts bank truncation
(`ASHA VER`); rejects a single word and a different person sharing the first name.

**Never applies to:** a credit-card account; a row whose counterparty type is BUSINESS; a row that
reads as salary; a row with `transferRejectedAt` set; a duplicate; a row already a refund, reversal,
investment transfer or transfer.

**Result:** `isTransfer = true`, `transferPairId = null`, status `TRANSFER`, and an explanation naming
the rule, the slot text and the owner name matched.

**Later pairing.** A one-sided transfer from rule 2 stays a pairing candidate. When the other leg is
imported later, rule 1 (or the existing gate) pairs them, so a leg whose own narration does not name
the user (a truncated single word) does not keep counting as spending.

## Seeing and undoing

- Rows display as transfers, exactly like paired transfers today.
- `TransactionExplanationService` gains two summaries, rendered by the existing web and mobile "why"
  panel with no client change:
  - rule 1: "Matched as a transfer between your own accounts: both sides carry reference …";
    evidence adds the reference and the other account;
  - rule 2: "Money moved between your own accounts: the sender (or payee) on this payment is you";
    evidence lists the slot name and the holder name matched.
- Undo is the existing "not a transfer" action. It already handles a null partner and sets
  `transferRejectedAt`, which keeps the automatic passes from re-applying it.

## Edge cases

- No holder name on any account: rule 2 has nothing to match; rule 1 still applies.
- A holder name edited later: the next run uses it; transfers already found stay until undone.
- Joint accounts: every printed holder name counts.
- Someone with exactly the user's name: accepted as rare; undo covers it.
- Performance: slot extraction and reference extraction run once per row, not per pair, inside the
  existing windowed lookups.

## Testing

Unit tests use synthetic narrations of the observed shapes; no real narration enters the repository.
Each new test must fail before the change and pass after it.

- Rule 1 pairs: each shape, including narrations with no "payment" word.
- Rule 1 non-pairs: equal amount with a different reference; a 10-digit phone number or 16-digit card
  number that coincides; two rows on the same account; a card-bill payment against another card.
- Rule 2 positives: every slot in the table; truncation; a second spelling of the owner's name.
- Rule 2 negatives: salary NEFT naming the owner as beneficiary; a business sender; a single-word slot;
  a different person with the same first name; the owner's name in free text; a card account;
  `transferRejectedAt` set; no holder names.
- Ordering and repeat runs: a rule-2 transfer pairs when its other leg arrives; undo sticks; running
  reconciliation twice gives the same result.
- Explanations: both new summaries and their evidence lines.

## Measurement

A throwaway probe (never committed) stages the corpus with the real parser, groups statements by
owner, runs the real reconciliation per owner before and after, and lists every row whose status
changes with the rule that changed it. Every changed row is read. Reported per owner: income,
spending and unresolved deltas. Any false positive blocks shipping until explained. Expected: spending
falls by own transfers out, unresolved falls by own transfers in, income falls only by genuine own
transfers, salary is unchanged.

Before the PR: backend `verify` (unit and integration). Web and mobile suites are re-run only if they
receive a code change.

## Out of scope

- Family tagging (Plan 2).
- A dashboard line for "moved between your accounts" (transfers are excluded from totals, as today).
- Last-digits account matching (adds nothing measured beyond rules 1 and 2).
- User-declared untracked accounts (rule 2 covers them through the user's own name).
- Any migration, new table or new screen.
