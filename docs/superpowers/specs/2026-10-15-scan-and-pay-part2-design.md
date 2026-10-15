# Scan & Pay — Part 2: Attach the scan's category to the statement row

**Status:** v2 approved by Sid on 2026-10-15. Not built.
**Date:** 2026-10-15
**Depends on:** Part 1 v3 (approved, `2026-10-15-scan-and-pay-part1-design.md`), which defines the `scan_payment_plans` table and its statuses.

---

## 1. Goal

When a bank statement is imported, the payment the user scanned in Part 1 should land in the category they picked at the counter, with no extra work.

A plan is never turned into a transaction. It only labels a row that the bank statement itself produced. If no row can be matched with confidence, nothing is attached automatically.

## 2. What already exists (checked on `origin/main`)

- **Categories are decided at staging, not at confirm.** `CategorizationService.suggestReadOnly` picks a category per staged row. The user sees it on the review screen, can change it, and then confirms. `ImportService` then copies the staged decision onto the `Transaction`. It maps the row's `categorySource` with `CategorizationService.decisionSourceFor`, and a review-screen change becomes `MANUAL` with `categoryManuallySet = true`.
- **`Transaction.DecisionSource`** has `GLOBAL_RULE, USER_RULE, LEARNED_PATTERN, KEYWORD_MATCH, SHARED_CORPUS, AI_FALLBACK, MERCHANT_DEFAULT, MANUAL, FILE_PROVIDED, STRUCTURAL_P2P`. The comment on the enum says the column is `VARCHAR(20)` with no DB CHECK, so a new value needs no migration. `SCAN_PLAN` (9 characters) fits.
- **`Transaction.referenceNumber`** holds a statement's reference, cheque or instrument number when the statement has that column. It is copied from staging at confirm.
- **`Transaction.counterpartyKey`** holds `vpa:<local-part>` when the narration carries a UPI ID (see Part 1, section 3).
- **`Transaction.txnDate`** is a date with no time.
- **`Transaction.Source`** is `MANUAL, CSV_IMPORT, GMAIL_IMPORT, ACCOUNT_AGGREGATOR`.
- **Side-effect rules after the category is set** (`CategorizationService.applySideEffectRules`, called from `ImportService` at confirm):
  - `MARK_INVESTMENT` does **not** replace a category when `categoryManuallySet` is true or the source is `MANUAL` (`investmentRuleToApply`).
  - `MARK_TRANSFER` has **no** such guard. It sets `transfer = true` and the reconciliation status to `TRANSFER` whatever the category is.
  - `ADD_TAG` only adds a tag.

## 3. When matching runs

1. **During staging,** after each row's normal category is suggested and before the review screen is built.
2. A matched row shows its category with the label **"From your scan"**. The user can change it on the review screen like any other row. A change made there wins, and is stored as `MANUAL` as today.
3. **At confirm,** the plan becomes `MATCHED` and stores the id of the transaction it labelled.
   - **In the same database transaction as the import.** `ImportService.confirmSession` is already `@Transactional`, and it marks the session `CONFIRMED` first, so a second confirm of the same session is already refused. The plan update happens inside that same transaction. Either the rows and the plan links are all saved, or none are.
   - **Guarded update.** Each plan is updated only if it is still `HANDED_OFF` (`UPDATE ... WHERE id = ? AND status = 'HANDED_OFF'`), and the number of rows changed is checked. It is not a load-then-save: the repo has a known gap where the version-number check only fires on `save()`.
   - **If 0 rows changed,** another import (for example a second account confirmed at the same moment) already matched this plan. The row then falls back to the category it would have had without the scan, and is flagged for review (`needsCategoryReview = true`). The import itself still succeeds.
   - **Database guard:** a unique index on `matched_transaction_id` (where not null), so one transaction can never be linked to two plans, whatever the code does.
4. **If the import is abandoned** before confirm, nothing changes. The plan stays `HANDED_OFF`.

The match is not recorded at staging, because staging can be abandoned, re-run or refreshed.

## 4. Which plans and rows can match

**Plans:**
- Belong to the importing user.
- Status `HANDED_OFF`.
- Not expired.
- Hidden plans are included, because hiding is display-only (Part 1, section 7).

**Rows:**
- Debits (money out) only.
- In any of the user's accounts. The UPI app chose the paying account, not Fynora, so the account can't narrow the search.

## 5. Match levels

Checked strongest first. A row can be matched by at most one level.

### Level 1: Exact reference
A value in the plan's `app_response_refs` equals the row's `referenceNumber`, or appears as a whole token in the row's narration. **And** the amount and date rules of Level 2 also hold. A reference alone is never enough.

The references come from the UPI app's reply to Fynora, so they are untrusted input:
- Only letters and digits are allowed, 6 to 35 characters long. Anything else is stored for diagnosis but never used to match.
- Values that are too generic, such as all zeros or `NA`, are never used.

**Gate:** this level is switched on only if phone test T4 (Part 1) shows that a returned reference really appears in a statement. Until then it is off, and only Levels 2 and 3 run.

### Level 2: Strong
All three must hold:

1. **Amount:** equal to the paisa.
2. **Date:** the row's `txnDate` falls between the hand-off date and the hand-off date plus the posting window.
   - The hand-off date is `handed_off_at` converted to **India time (Asia/Kolkata)** before taking the date. `handed_off_at` is UTC, so a payment at 23:50 IST would otherwise land on the previous or next day.
   - **Posting window:** not established. It must cover the gap between when a payment happens and the date the bank prints for it. Proposed starting value: 2 days. It is checked against tester data in the phone tests before release (section 11).
3. **Payee agrees,** in this order:
   1. The narration contains a full UPI ID, and it equals `payee_vpa` (ignoring case). This is a match.
   2. The narration contains one or more full UPI IDs and **none** equals `payee_vpa`. This is **not** a match at any level.
      - Some narrations may also carry the payer's own UPI ID. That is why the rule is "none equals", not "a different one is present". **Not established:** which banks print the payer's own ID. M4 measures it.
   3. The narration has no full UPI ID, but the row's `counterpartyKey` is `vpa:` and equals the plan's `counterparty_key`. This is a match.
   4. Anything else counts as no payee information, so the row falls to Level 3.

The row's weak keys (`cut:`, `masked:`, `name:`) are not enough for Level 2. A `cut:` key that is a prefix of the plan's full UPI ID, or a `masked:` key whose tail and handle match the plan's, is passed to Level 3 as supporting evidence and shown to the user. It is never auto-attached.

### Level 3: Amount and date only
The amount and date rules of Level 2 hold, but there is no payee information, or only a weak key.

This level is **never attached automatically.** The review screen asks: **"Was this your ₹<amount> scan to <name on QR> on <date>?"** with Yes / No.
**Only UPI rows qualify.** A card payment, an ATM withdrawal or a bank transfer with the same amount and date must not be offered as a candidate. Level 3 therefore only considers rows whose narration carries a UPI marker (the token `UPI`, ignoring case). **Not established:** that every bank prints that token on UPI rows. M6 measures it on the corpus. If some bank does not, its rows can only match at Level 2.

**What the question shows:**
- The scan: name on QR, amount, scan date and time, and category.
- The row: date, amount, narration and account.

At most one question per plan per import. If a plan has several Level 3 candidates, they are listed together under that one plan, plus "None of these".

**Answers:**
- **Yes:** attaches the plan. The row is stored as a user decision (`MANUAL` source), and the plan is linked with `match_level = USER_CONFIRMED`.
- **No / None of these:** nothing is attached, and the plan stays `HANDED_OFF`. At confirm, a rejection record (plan id, transaction id) is saved. That pair is never offered again, including on a statement refresh of the same import.
- **No answer:** the user can confirm the import without answering. That counts as "not now": nothing is attached and nothing is recorded, so the question can come back in a later import.

## 6. Ambiguity rule

A plan is attached automatically (Level 1 or 2) only if **both** are true:

- The plan has exactly one possible row at that level in this import.
- That row has exactly one possible plan at that level.

Otherwise it is ambiguous, for example two ₹50 tea payments to the same shop on the same day. The review screen shows the candidates side by side, and the user picks which plan goes with which row, or none.

This rule comes from the standing project rule that a guess is never recorded as fact. A wrong automatic attach would show the user a confident, wrong category.

**Across imports:** a plan already `MATCHED` is out of the pool. If a second import includes the same payment again (an overlapping statement period), the plan is not considered again. How that repeated row itself is treated is unchanged by this design.

## 7. What attaching does

On the transaction, at confirm:

| Field | Value |
|---|---|
| `categoryId` | The plan's category |
| `decisionSource` | New value `SCAN_PLAN` (Level 1 or 2), or `MANUAL` (Level 3 after the user said Yes, or any review-screen change) |
| `categoryManuallySet` | `true`. The category came from the user's own choice at the counter. |
| `needsCategoryReview` | `false` |

On the plan:

| Field | Value |
|---|---|
| `status` | `MATCHED` |
| `matched_transaction_id` | New column, the transaction labelled |
| `match_level` | New column, `EXACT_REF`, `STRONG` or `USER_CONFIRMED` |
| `matched_at` | New column |

Effects of `categoryManuallySet = true`, using existing behaviour:
- `MARK_INVESTMENT` rules do not replace the category (`investmentRuleToApply` already guards this).
- A later statement refresh keeps the category, as it does for manual categories today.

**`CategorizationService.decisionSourceFor`** gets the new staging source mapped to `SCAN_PLAN`.

### Transfer rules: decision needed
`MARK_TRANSFER` has no manual-category guard. If the user has a rule that marks payments to some payee as transfers, and that payee's QR was scanned, the row would carry the scan's category **and** be marked as a transfer. Transfers are left out of spending totals, so the scanned category would effectively vanish from reports.

**What a transfer means in Fynora (checked on `origin/main`):**
- `FlowClassifier` classifies a row with `isTransfer` as `TRANSFER` for the reason `OWN_ACCOUNT_TRANSFER`.
- The spending queries in `TransactionRepository` filter on `t.isTransfer = false`.

So a transfer means money moving between the user's **own** accounts, and it is left out of spending.

That changes the earlier proposal. A QR scan is **usually** a payment to someone else, but not always. A user can scan their own QR, for example to move money to their other bank. A transfer rule the user wrote for that payee is then exactly right.

Options:
- **A. Every transfer rule wins.** The scan's category is kept on the row, but the row is out of spending.
- **B. No transfer rule applies to `SCAN_PLAN` rows.** Wrong for the own-QR case above.
- **C. The user's own transfer rules win, and global transfer rules are skipped for `SCAN_PLAN` rows.** The user's rule is a deliberate statement about that payee. A global rule is a generic narration pattern that cannot know the user just paid someone else.

**Decided: C** (Sid, 2026-10-15). Before release, the effect is tested on reporting totals: a `SCAN_PLAN` row hit by a user transfer rule is absent from spending, and one hit by a global rule is present.

## 8. Undo

- **If the matched transaction is deleted** (soft delete), or its whole import is reverted, the plan returns to `HANDED_OFF` and the match columns are cleared. It can then match again in a later import.
- **If the user changes the transaction's category later** in the Ledger, the plan stays `MATCHED`. The user's later choice simply wins, as with any manual edit.
- **Not established:** every code path that deletes or reverts transactions. All of them must be found and covered before implementation, so that no plan stays `MATCHED` to a transaction that no longer exists.

## 9. Expiry

A plan that never matches must eventually leave the pool, or it could match an unrelated payment months later.

The window should come from how late users actually import statements, not a guess. Proposed measurement, which needs Sid's go-ahead because it reads production:

- For `CSV_IMPORT` transactions created in the last 90 days, compute `created_at` (India date) minus `txn_date`.
- Report only the distribution: median, 90th and 99th percentile, in days.
- Read only counts and day gaps. No row content, ids or narrations.

**Proposal:** set expiry at the 99th percentile, rounded up to a whole week. An expired plan moves to `EXPIRED` and leaves the pool. Whether expired plans are shown to the user is Part 1's open question 2.

## 10. Scope

**In the first version:** CSV and PDF statement imports (`CSV_IMPORT` source), the path described in section 2.

**Not in the first version:**
- **Bank-linked accounts** (`ACCOUNT_AGGREGATOR`, through Setu). The code is there, with background sweeps enabled by default. **Not established:** whether any user's transactions come in this way today. If the production check (count of `ACCOUNT_AGGREGATOR` transactions in the last 30 days) is above zero, this path needs the same matching, and is added as a follow-up before release to everyone.
- **Gmail sync** (`GMAIL_IMPORT`) is paused, so it waits.
- **Manually added transactions** do not match. The user typed them, so they already chose the category.

**Left out on purpose:** feeding scan choices into the shared merchant corpus (`CounterpartyCategoryObservation`). That would change what other users' suggestions learn from, which is a privacy and product decision for Sid. It is not a side effect of Part 2.

## 11. Measurements and tests before release

| # | What | How | Decides |
|---|---|---|---|
| M1 | Import lag (section 9) | Production read, counts and day gaps only, after Sid's go-ahead | Expiry window |
| M2 | Posting window (section 5) | During Part 1 phone tests, record the hand-off date and the date the bank printed for the same payment | Level 2 date window |
| M3 | Do returned references appear in statements? | Part 1 test T4 | Whether Level 1 is on |
| M4 | How often the narration of a scanned payment has a full UPI ID, a `vpa:` key, only a weak key, or nothing | On tester statements for the scanned payments | How many matches land in Level 2 vs Level 3 |
| M6 | Do UPI rows always carry the token `UPI`? | On the real statement corpus: of rows with a `vpa:` key, the share whose narration contains `UPI`, per bank | Whether the Level 3 UPI-only filter loses any bank |
| M5 | Bank-linked accounts in use? | Production count of `ACCOUNT_AGGREGATOR` transactions in 30 days | Whether section 10's follow-up is needed |

Automated tests:

- **Each match level:** a match, and each failing condition separately (amount off by ₹0.01, date one day past the window, a different full UPI ID).
- **India-time boundary:** a hand-off at 23:50 IST, stored as 18:20 UTC, matches a row dated the same IST day.
- **Ambiguity:**
  - Two plans and one row.
  - One plan and two rows.
  - Two and two.
  - None of them auto-attach.
- **A different full UPI ID** in the narration blocks the match at every level.
- **Abandoned import** leaves every plan `HANDED_OFF`.
- **Review-screen change** of a matched row stores `MANUAL`, and the plan is still linked.
- **Side-effect rules:**
  - A `MARK_INVESTMENT` rule does not replace a `SCAN_PLAN` category.
  - The `MARK_TRANSFER` behaviour matches whichever option Sid picks in section 7.
- **Undo:** deleting the transaction returns the plan to `HANDED_OFF`, and a re-import then matches again.
- **Statement refresh** keeps the `SCAN_PLAN` category.
- **Users are isolated:** a plan never matches another user's row.
- **Two imports confirmed together:** two sessions for different accounts both staged the same plan and are confirmed concurrently. Exactly one links it, and the other's row falls back and is flagged for review. This uses a real database, not mocks.
- **Unique index:** linking a second plan to the same transaction fails at the database.
- **Failure in the middle of confirm:** an error after rows are written rolls back both the rows and the plan links.
- **Level 1 safety:**
  - A matching reference with the wrong amount does not match.
  - A generic or malformed reference is never used.
- **Several UPI IDs in a narration:** with the payer's ID plus the payee's, it matches. With the payer's ID plus a different payee's, it does not.
- **Non-UPI rows:** a card or ATM row with the same amount and date is never a Level 3 candidate.
- **Rejections:** a rejected (plan, transaction) pair is not offered again after a statement refresh.

**Release gates.** Same rules as Part 1, section 13a. "Fixed" follows from the design. "Proposed" is a starting number for Sid to confirm, not measured.

| Gate | Limit | Kind |
|---|---|---|
| Wrong automatic matches reported in the tester phase | 0 | Fixed. Any one blocks release, and its cause is traced first. |
| A transaction linked to two plans | 0 | Fixed. The unique index makes it impossible, and any attempt is logged. |
| A plan `MATCHED` to a deleted transaction | 0 | Fixed. A daily check counts these. |
| Share of scanned payments that end up matched (Level 1 + 2 + user-confirmed), once their statement is imported | Report only in the tester phase | Proposed. A target is set from tester data. |
| After release, turn off matching (scans keep working) if a wrong-match report is confirmed | Any | Fixed |
- **End-to-end:** a real statement row is matched by value, in the style of the repo's existing PDF import seam test, from scan plan through file bytes to the confirmed row and category.

## 12. Open decisions for Sid

1. ~~**Transfer rules** (section 7)~~ Decided 2026-10-15: C. User transfer rules win, and global ones are skipped for `SCAN_PLAN` rows.
2. **Go-ahead for the production reads** M1 and M5 (counts only).
3. **Shared merchant corpus:** should scan choices ever feed it? Proposed: not now, revisit after launch with a privacy review.
