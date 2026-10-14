# Rows that name nobody get no merchant

Status: design approved section by section by the owner on 2026-10-14; this document awaits the
owner's review before an implementation plan is written.

## Problem

Every imported transaction is linked to a merchant (`transactions.merchant_id`), and a merchant is
created on first sight of a payee. Some bank narrations name no payee at all: a UPI line carrying
only a reference number ("UPI/RRN &lt;n&gt;/UPI"), a NACH line carrying only a mandate number, or a
cash withdrawal at an ATM. The engine still creates a merchant for each.

- **Each such row gets its own merchant.** `MerchantNormalizationEngine.resolve` looks a row up by
  its whole normalised narration first, reference number included, which is unique per row; the
  grouping key, its second lookup, is null for a narration made only of payment words. So the row
  can never match an existing merchant. On the statement corpus (34 statements, 1,965 rows) 165
  such rows made 165 merchants, each holding exactly one row: 16% of all engine-made merchants.
- **What a customer sees.** Since #2133 these merchants are named "UPI payment", "NACH payment" and
  so on. Top Merchants and the needs-review merchant groups list them as if they were shops, and
  the dashboard's "merchants identified" count includes them.
- **What the engine learns.** A category the customer gives such a row is learned for a merchant no
  later row ever lands on: it teaches nothing.
- **What the counterparty review misses.** `TransactionGroupingService.groupNeedsReviewByCounterparty`
  skips every row that has a merchant, so these rows never join it, even when they carry a UPI id.
- **ATM withdrawals.** The codebase already treats an ATM withdrawal as a mechanism with no business
  on the other side (`MerchantIdentityLookup.NON_ENTITY_TERMS`). They still get a merchant per ATM
  place, named after the narration ("Atm Wdl &lt;place&gt;", "Atw &lt;card&gt; &lt;terminal&gt;
  &lt;place&gt;"). The only ATM formats in the corpus are HDFC's "ATW-" and "NWD-" (1 row each).

Production (owner's count-only queries, 2026-10-14): 2 merchants named after a rail alone, holding
5 rows of 2 users, every one of those rows typed BUSINESS with a counterparty key, none with a
learned category; 1 merchant opening with "Atm" (1 row). Merchants opening with "Atw" or "Nwd" were
not counted.

## Decisions (owner, 2026-10-14)

1. Such a row gets **no merchant** (not a shared one, not one keyed by its counterparty).
2. Scope is **narrow**: rows that name nobody, plus ATM withdrawals in the formats seen (ATM, ATW,
   NWD). Other mechanism rows (salary, interest, charges, EMI) are out of scope.
3. Existing rows are **detached** by a one-time job, dry-run first.
4. Approach: **one rule at the source** -- the engine decides, every caller handles the answer.

## Design

### 1. The rule

`MerchantNames.namesNobody(String description)` is true when either:

- the narration has no structured payee field, and the name a new merchant would get shows as a
  rail's payment: `MerchantNames.forDisplay(MerchantNames.forNarration(d))` is one of the labels in
  `MerchantNames.RAIL_PAYMENTS` ("UPI payment", "NACH payment", "Mobile banking payment", "Card
  payment", ...). This covers "UPI/RRN &lt;n&gt;/UPI", a QR payment left with a handle suffix
  ("Ybl"), and a narration left with a direction word ("Upi Dr"); or
- the first word of the normalised narration is `atm`, `atw` or `nwd`.

A narration with a structured payee field is never "nobody". The rule reuses #2133's naming, so
"names nobody" has one definition in the codebase.

### 2. The engine

`MerchantNormalizationEngine.resolve(userId, description)` applies the rule **first**, before the
exact-alias lookup: an alias written before this change would otherwise hand the old merchant
back. It returns `Optional<Merchant>`, empty for such a row. The return type changes on purpose, so
that the compiler names every caller. `resolveReadOnly` (both overloads) returns empty the same way.

### 3. The callers

| Caller | With no merchant |
|---|---|
| `CategorizationService.suggest` | Runs as `suggestReadOnly` already does with no merchant: rules get no merchant name, the learned distribution is skipped, keywords / shared corpus / AI fallback / shop trade / P2P / bank activity run as now. `Suggestion.merchantId` is null. |
| `CategorizationService.learn`, `queueLearning` | Do nothing. A correction stays on that row and never reaches unrelated payees. |
| `CategorizationService.resolveMerchantId` | Returns null. |
| `CategorizationService.applySideEffectRules` | Runs with no merchant name. |
| `ImportService`, `StatementRefreshService` (2), `TransactionService` (create, edit) | Store no merchant. Editing a row's description into one that names nobody clears its merchant. |
| `TransactionNormalizer` (import preview) | Already handles an empty `resolveReadOnly`; no change expected, to be confirmed by test. |

Readers that already handle a row with no merchant (`AnalyticsService`, `TransactionGroupingService`,
`WorkspaceDashboardService`, `DataExportService`, `TransactionExplanationService`,
`TransactionService`) need no change; tests confirm the rows drop out of Top Merchants and the
merchant groups and into the counterparty groups. Web and mobile clients do not read a
transaction's merchant id (only `MerchantGroup.merchantId`, built from rows that have one).

### 4. Detaching existing rows

- **Queue.** Migration `V269` (free on main and in every open PR on 2026-10-14) creates
  `merchant_nobody_detach` (merchant id, primary key, `ON DELETE CASCADE`) and queues every
  TEMPORARY merchant whose name's first word is one of the payment words the rule can produce or
  meet: the nine rails (`upi`, `imps`, `neft`, `rtgs`, `mob`, `mmt`, `ib`, `ach`, `nach`), `pos`,
  `atm`, `atw`, `nwd`. The names #2133 stores ("UPI payment", "NACH payment" ...) open with one of
  these words already.
- **Sweep.** `MerchantNobodyDetachSweepService`, built like `MerchantRailNameSweepService`:
  - per queued merchant that is still TEMPORARY, every live row whose narration `namesNobody` gets
    `merchant_id = NULL`, written only where the row still points at that merchant;
  - the merchant is deleted only if it then has no rows **and** no learned category
    (`merchant_category_learning`) -- deleting would cascade the learning and its audit history, as
    `MerchantReviewService`'s own comment warns; a merchant left with learning is kept and counted;
  - its aliases go with it (`ON DELETE CASCADE`), so a later import of the same narration meets the
    rule, not a dead alias;
  - each detach is audited as a new action, `MERCHANT_ROWS_DETACHED`, and each deletion with the
    existing `MERCHANT_DISCARDED` (actor `merchant-nobody-detach`); counts only, no narration;
  - modes `app.merchant-nobody-detach.sweep.mode`: `dry-run` (default; reads the whole queue once
    per start and logs rows it would detach, merchants it would delete, merchants it would keep),
    `apply`, `off`. Bounded batches; one batch per transaction; a DB error fails the batch.
- **Before apply,** the owner runs a count-only query (to be written with the plan) for the same
  set, ATW/NWD included, so the dry-run numbers have something to be checked against.

## Verification

1. Test-first throughout; each new test watched failing.
   - `MerchantNamesTest`: the rule on invented narrations of every shape above, and on ones that do
     name a payee ("UPI/.../SAMPLE STORE/...", "POS &lt;card&gt; SAMPLE MART", "NEFT ... SAMPLE TRADERS").
   - `MerchantNormalizationEngineTest`: no merchant, no alias written; an old alias for the same
     narration is not used.
   - `CategorizationService` tests: suggest without a merchant (ATM row still "Cash Withdrawal"),
     learn and queueLearning do nothing.
   - ITs against Postgres: import, manual create, description edit, statement refresh each leave the
     row with no merchant; the row joins the counterparty groups and leaves Top Merchants.
   - `MerchantNobodyDetachSweepIT`: detaches only rows that name nobody; deletes only empty merchants
     without learning; leaves APPROVED merchants; dry-run writes nothing; a row repointed meanwhile
     is not touched.
2. Corpus: every statement imported into a fresh account, before and after. Every row that does not
   name nobody keeps the same merchant grouping; the rows that do get none. Expected about 165 + 2;
   the measured counts go in the PR.
3. Production count (owner) before apply; dry-run log compared against it.
4. Full `./mvnw verify`, then the PR's full CI run (repository is private: `full-ci` label + push).

## Out of scope

- Other mechanism rows (salary, interest, charges, EMI, card bill payments).
- Grouping rows by counterparty key as a merchant identity.
- Narrations whose structured payee field is itself a code (one corpus row); noted, not changed.

## Sequencing

#2153 (card-purchase display) changes `MerchantNames` too. This work starts from main after #2153
merges, or is rebased onto it.
