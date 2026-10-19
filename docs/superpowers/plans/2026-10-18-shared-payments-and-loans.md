# Shared Payments and Loans Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A credit that is someone's share of a bill lowers that bill; money lent or borrowed is neither
spending nor income; one balance per person; the app suggests and the user confirms.

**Architecture:** One pure class, `PeopleLedger`, turns the user's links, rules and loan entries plus
their person rows into everything the figures need (shared amount per bill, rows to drop, a flow decision
per credit, balances). Rules are applied when the ledger is built, never stored as links, so a rule
covers history and removing it undoes it. Every figure reads the ledger through two choke points:
`ReportRows` (replaces the 12 `RefundNetting.reportable(rows, ccPaymentFromTransactionIds(rows))` call
sites and the 10 `RefundNetting.from` builds) and `FlowTotals.Context` (built in one place,
`InflowChoiceService.contextFor`). Mobile reads suggestions and balances from new `/api/v1/people`
endpoints.

**Tech Stack:** Spring Boot / JPA / Flyway / Postgres (backend), Expo React Native + React Query (mobile).

**Spec:** `docs/superpowers/specs/2026-10-18-shared-payments-and-loans-design.md`

## Global Constraints

- Free for every user: no entitlement check anywhere in this feature.
- No AI in matching or suggestions; no person name, UPI id or narration in any outbound call.
- Nothing changes a figure until the user confirms a link, an entry or a rule.
- Per-person rules and balances only on keys `CounterpartyIdentity.identifiesOnePayee` accepts; weak keys
  get one-off links only.
- A share is counted in the bill's month and category; a share never takes a bill below zero.
- "Let it go" re-files the lent debit under the system category "Gifts & Donations", in its own month.
- Windows: bill picker 14 days before a credit then 14 after; "lent?" after 30 days with nothing back;
  "settle it" after 60 days; loan look-back 180 days.
- Out of scope: lending through a card EMI, wage-deducted advances, a household view, the website.
- Migration number: V279 at the time of writing — `git fetch origin` and list
  `backend/src/main/resources/db/migration` before adding; never renumber an existing one.
- Commit messages: conventional scope from the commitlint list (`transactions`, `mobile`, `db`, …);
  never a `Co-Authored-By` or any AI-attribution line.
- Mobile ships with the next binary; never published as an OTA to 1.0.0.
- No real names, ids or amounts in code, tests, comments or PR text (synthetic forms:
  `vpa:flatmate.a`, `100000000001`).
- Mobile commands run on Node 22: `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 node_modules/.bin/jest`.

## Review Focus

1. **A figure-reading caller that still builds netting the old way** — rent stays at the full bill on
   that one screen. Pinned by `ReportRowsGuardTest` (Task 3) and `PeopleFiguresIT` (Task 3).
2. **A deleted, superseded or duplicate transaction still inside a link** — a share keeps lowering a
   bill whose credit was removed by a re-upload. Pinned by `PeopleLedgerTest.deadRowsDropOutOfLinks` (Task 2).
3. **A rule and a one-off answer on the same credit** — counted twice. Pinned by
   `PeopleLedgerTest.explicitAnswerOutranksRule` (Task 2).
4. **Two bills of the same identity in the window** (rent paid in two parts) — the rule-derived share
   splits across both by remaining amount, never exceeds either. Pinned by
   `PeopleLedgerTest.ruleSpillsAcrossTwoBillsOfSameIdentity` (Task 2).
5. **A month-scoped report whose bill is in the month but whose share arrives next month** — the share
   must still lower it. Pinned by `PeopleFiguresIT.shareInNextMonthLowersThisMonth` (Task 3).

---

## Part A — server (PR A)

### File structure (new package `com.finora.people`)

| File | Responsibility |
|---|---|
| `db/migration/V281__people_links.sql` | 5 tables, ack-kind constraint widened |
| `entity/ShareLink.java`, `SplitRule.java`, `LoanEntry.java`, `PersonSetting.java`, `PeoplePreference.java` | JPA entities |
| `repository/…Repository.java` (5) | Spring Data repositories |
| `people/PeopleLedger.java` | Pure: links + rules + entries + rows → figures and balances |
| `people/PeopleLedgerService.java` | Loads what the ledger needs for one user |
| `people/ReportRows.java` | The one place reports get their rows and netting |
| `people/PeopleSuggestions.java` | Pure: ledger + rows → suggestions A–G and expected shares |
| `people/PeopleService.java` | Writes: link, unlink, entry, settle, rule, settings |
| `people/PeopleController.java`, `people/PeopleDtos.java` | `/api/v1/people` |

### Task 1: Tables, entities, purge, export, change stamp

**Files:**
- Create: `backend/src/main/resources/db/migration/V281__people_links.sql`, the five entities and repositories above.
- Modify: `AccountPurgeSweepService.java` (delete the five by user, next to `insightAcknowledgementRepository.deleteByUserId`),
  `DataExportService.java` + `DataExportDto.java` (five export lists, as `InsightAcknowledgementExportDto`),
  `ChangeStampService.java` (hash the five tables into the `transactions` value, as `sender_inflow_rules` is),
  `InsightAcknowledgementDto.java` (`AckKind` gains the six kinds below).
- Test: `AccountPurgeSweepServiceIT`, `DataExportServiceIT`, `ChangeStampServiceIT` (or the existing stamp test), `InsightAcknowledgementsIT`.

**Interfaces:**
- Produces tables (all `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `id UUID` default `gen_random_uuid()`):
  - `share_links(credit_txn_id UUID NOT NULL REFERENCES transactions(id), bill_txn_id UUID NOT NULL REFERENCES transactions(id), amount NUMERIC(15,2) NOT NULL CHECK (amount > 0), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE (credit_txn_id, bill_txn_id))`
  - `split_rules(sender_key VARCHAR(200) NOT NULL, bill_category_id UUID NOT NULL REFERENCES categories(id), bill_payee_key VARCHAR(200), bill_merchant VARCHAR(300), created_at, removed_at TIMESTAMPTZ, CHECK (bill_payee_key IS NOT NULL OR bill_merchant IS NOT NULL))`
  - `loan_entries(person_key VARCHAR(200) NOT NULL, txn_id UUID REFERENCES transactions(id), direction VARCHAR(20) NOT NULL CHECK (direction IN ('LENT','PAID_BACK','BORROWED','REPAID','CASH_SETTLED','LET_GO')), amount NUMERIC(15,2) NOT NULL CHECK (amount > 0), created_at, UNIQUE (txn_id, direction))`
  - `person_settings(person_key VARCHAR(200) NOT NULL, family BOOLEAN NOT NULL DEFAULT false, UNIQUE (user_id, person_key))`
  - `people_preferences(user_id UUID PRIMARY KEY, own_upi_id VARCHAR(100), updated_at TIMESTAMPTZ NOT NULL DEFAULT now())`
  - `insight_acknowledgements.kind` CHECK adds `SHARE_CHECK`, `LENT_CHECK`, `PAYBACK_CHECK`, `SETTLE`, `EVERY_MONTH`, `ALWAYS_PERSON`.
- Produces repositories with `findByUserId(UUID)` and `deleteByUserId(UUID)` each; `ShareLinkRepository.findByUserIdAndBillTxnId`, `LoanEntryRepository.findByUserIdAndPersonKey`.

- [ ] **Step 1: Failing tests.** `AccountPurgeSweepServiceIT.purgeDeletesPeopleTables` (seed one row in each table, purge, assert all five counts 0); `DataExportServiceIT.exportIncludesPeopleTables` (each list has its one row); stamp test `transactionsStampMovesWhenAShareLinkIsAdded`; `InsightAcknowledgementsIT.acceptsPeopleKinds` (POST `SHARE_CHECK` → 200).
- [ ] **Step 2: Run** `./mvnw verify -Dit.test='AccountPurgeSweepServiceIT,DataExportServiceIT,InsightAcknowledgementsIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL (tables/kinds missing).
- [ ] **Step 3: Implement** migration, entities, repositories, purge, export, stamp, ack kinds.
- [ ] **Step 4: Run** the same command — Expected: PASS.
- [ ] **Step 5: Commit** `feat(db): tables for shared payments and loans`.

### Task 2: `PeopleLedger` — the pure core

**Files:**
- Create: `backend/src/main/java/com/finora/people/PeopleLedger.java`
- Test: `backend/src/test/java/com/finora/people/PeopleLedgerTest.java`

**Interfaces:**
- Consumes: Task 1 entities.
- Produces:
  ```java
  public final class PeopleLedger {
    public static final PeopleLedger EMPTY;
    public static PeopleLedger build(Collection<Transaction> rows, Collection<ShareLink> links,
        Collection<SplitRule> rules, Collection<LoanEntry> entries, Set<String> repaymentSenderKeys);
    public Map<UUID, BigDecimal> sharedByBillId();          // bill id -> total of its shares
    public Set<UUID> droppedIds();                           // share credits + LENT/REPAID debits not let go
    public FlowClassifier.FlowDecision creditDecision(Transaction t); // null when the ledger says nothing
    public List<Share> sharesOf(UUID billId);
    public Map<String, Balance> balances();                  // by person key, non-zero only
    public record Share(UUID creditId, UUID billId, BigDecimal amount, boolean fromRule) {}
    public record Balance(String personKey, BigDecimal owedToUser, LocalDate since) {} // negative = user owes
  }
  ```
  `rows` = the user's live rows that matter (Task 3 decides which); a row absent from `rows`, or a
  duplicate (`isDuplicateOf != null`) or `SUPERSEDED`, makes every link and entry that names it vanish.

**Algorithm (what the tests do not determine):**
1. Explicit links first, each capped at `min(link.amount, credit unlinked remainder, bill remainder)`.
2. Then each active split rule, credits from `sender_key` in date order that have no explicit link and no
   loan entry: candidate bills are expenses with `categoryId == bill_category_id` and
   (`counterpartyKey == bill_payee_key` or, when that is null, `merchant name equalsIgnoreCase bill_merchant`),
   dated credit−14d … credit+14d, ordered by days before the credit first (nearest first), then days
   after. Fill the first candidate's remainder, spill into the next; stop when the credit is used up.
3. Loan entries: `LENT` and `REPAID` debits go into `droppedIds` unless a `LET_GO` entry names the same
   transaction. A credit with a `PAID_BACK` entry → `ADJUSTMENT/PAID_BACK`; `BORROWED` → `LIABILITY/BORROWED`.
4. Repayment rule (`repaymentSenderKeys`, the user's sender rules on the built-in Paid back kind): a credit
   from that key with no link or entry counts as `PAID_BACK` toward the balance **only while that
   person's balance is positive**, and only up to it.
5. Balance per person = LENT − PAID_BACK − CASH_SETTLED − LET_GO + REPAID − BORROWED, in that sign
   convention (positive: they owe the user). `since` = date of the oldest entry still open (FIFO).

New `FlowReason`s in `FlowClassifier`: `SHARE_OF_BILL`, `BORROWED`, `LENT`, `REPAID_BORROWING`.

- [ ] **Step 1: Failing tests** (synthetic keys `vpa:flatmate.a`, `vpa:friend.k`; amounts in whole rupees):
  - `explicitShareLowersItsBill`: bill 30000, link 7500 → `sharedByBillId().get(bill) == 7500`, credit in `droppedIds()`.
  - `shareCappedAtBillRemainder`: bill 1000, links 700 + 700 → shares 700 and 300.
  - `shareExactlyEqualToBill` / `onePaiseOverIsCapped`: 1000 vs 1000 → 1000; 1000.01 → 1000.
  - `creditAcrossTwoBills`: credit 1500 linked 1000 + 500 to two bills → both recorded.
  - `ruleLinksEveryMonthBackwards`: rule (sender a, Rent, payee owner); 3 months of bill+share → 3 shares, `fromRule` true.
  - `ruleSpillsAcrossTwoBillsOfSameIdentity`: two Rent bills 15000 each 2 days apart, share 20000 → 15000 + 5000.
  - `rulePrefersBillBeforeCredit`: bills 5 days before and 3 days after → the before one.
  - `ruleIgnoresBillOutside14Days`: bill 15 days before → no share.
  - `explicitAnswerOutranksRule`: rule matches, but the credit has a `PAID_BACK` entry → no share, decision PAID_BACK.
  - `deadRowsDropOutOfLinks`: link whose credit is a duplicate / superseded / absent → no share, nothing dropped.
  - `lentDebitIsDroppedUntilLetGo`: LENT → dropped; + LET_GO → not dropped.
  - `balanceGoesToZeroAndPast`: LENT 2000, PAID_BACK 1500 → 500; + PAID_BACK 800 → −300.
  - `repaymentRuleStopsAtZero`: LENT 1000, rule, credits 600 + 600 → first PAID_BACK, second counts 400, balance 0, second's decision null beyond.
  - `weakKeyGetsNoRuleMatch`: rule on `cut:abc` → no share (the rule is ignored).
- [ ] **Step 2: Run** `./mvnw test -Dtest=PeopleLedgerTest` — Expected: FAIL (class missing).
- [ ] **Step 3: Implement** `PeopleLedger` per the interface and algorithm.
- [ ] **Step 4: Run** the same — Expected: PASS, all 15.
- [ ] **Step 5: Commit** `feat(transactions): people ledger for shares and loans`.

### Task 3: Every figure reads the ledger

**Files:**
- Create: `people/PeopleLedgerService.java`, `people/ReportRows.java`
- Modify: `RefundNetting.java` (add `withShares(Map<UUID,BigDecimal>)` and the drop set in `reportable`),
  `FlowTotals.java` (`Context` gains `PeopleLedger people`; `decision` asks `people.creditDecision(t)` first),
  `FlowClassifier.java` (new reasons; `VERSION = 9` with a comment line),
  `InflowChoiceService.contextFor` (fills `people`),
  the call sites in `DashboardService`, `ReportService`, `AnalyticsService`, `InsightsService`, `BudgetService`.
- Test: `ReportRowsGuardTest`, `PeopleFiguresIT`, existing `FlowClassifierTest`, `RefundNettingTest`.

**Interfaces:**
- Consumes: `PeopleLedger` (Task 2).
- Produces:
  ```java
  public class PeopleLedgerService { public PeopleLedger forUser(UUID userId); }
  public class ReportRows {
    public List<Transaction> reportable(UUID userId, Collection<Transaction> rows); // ccPayment ids ∪ ledger.droppedIds()
    public RefundNetting netting(UUID userId, RefundNetting refunds);               // refunds.withShares(ledger.sharedByBillId())
  }
  ```
  `forUser` loads links, rules, entries, the user's Paid-back sender keys, and the rows they need: every
  row a link or entry names plus every PERSON-counterparty credit and every expense in a rule's category,
  across all time on live accounts. Precedence in `FlowClassifier.inflow`: transfer/refund/reversal
  pairing, then `people.creditDecision`, then the chosen kind (unchanged below).

- [ ] **Step 1: Failing tests:**
  - `ReportRowsGuardTest.noCallerBypassesReportRows`: scans `backend/src/main/java` for `ccPaymentFromTransactionIds(` and `RefundNetting.from(`; only `ReportRows`, `TransactionGraphService` and `RefundNetting` may contain them.
  - `PeopleFiguresIT` (one user; Rent bill 30000 on day 1; three shares 7500 on days 3–7 linked; a LENT debit 2000):
    `dashboardMonthSpendCountsTheShare` (Rent 7500, lent absent), `reportMonthMatchesDashboard`,
    `analyticsRangeCountsTheShare`, `insightsMonthLineCountsTheShare`, `budgetRentSpentIsTheShare`,
    `shareInNextMonthLowersThisMonth` (bill on the 30th, share on the 3rd of next month),
    `shareCreditIsNotUnresolvedInflow` (unresolved count 0).
  - `FlowClassifierTest.ledgerDecisionOutranksChosenKind`.
- [ ] **Step 2: Run** `./mvnw test -Dtest='ReportRowsGuardTest,FlowClassifierTest'` and `./mvnw verify -Dit.test=PeopleFiguresIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL.
- [ ] **Step 3: Implement** `PeopleLedgerService`, `ReportRows`; replace each call site; bump `VERSION`.
- [ ] **Step 4: Run** the same — Expected: PASS; then `./mvnw test` — Expected: all unit tests pass (fix any test that constructed `Context` with the old arity by using the 4-argument constructor plus `PeopleLedger.EMPTY`).
- [ ] **Step 5: Commit** `feat(transactions): reports, budgets and insights count only your share`.

### Task 4: Writes and the people API

**Files:**
- Create: `people/PeopleService.java`, `people/PeopleController.java`, `people/PeopleDtos.java`
- Test: `people/PeopleServiceTest.java`, `controller/PeopleControllerIT.java`

**Interfaces:**
- Consumes: `PeopleLedgerService.forUser`, repositories (Task 1), `InflowKindService.setChoice` for the repayment rule.
- Produces endpoints (all scoped to the current user; a transaction id not the user's → 404):
  | Method + path | Body | Effect |
  |---|---|---|
  | `POST /api/v1/people/shares` | `{creditId, billId, amount?}` | link; amount defaults to the cap |
  | `DELETE /api/v1/people/shares?creditId=&billId=` | | unlink |
  | `POST /api/v1/people/entries` | `{txnId, direction}` (LENT, PAID_BACK, BORROWED, REPAID) | entry for that row's full amount, person = its key |
  | `DELETE /api/v1/people/entries/{id}` | | undo |
  | `POST /api/v1/people/settle` | `{personKey, how: CASH \| LET_GO}` | CASH_SETTLED for the open balance; LET_GO also re-files each open LENT debit under "Gifts & Donations" |
  | `POST /api/v1/people/split-rules` | `{creditId, billId}` | rule from that pair (sender = credit's key, identity = bill's category + payee key, else merchant) |
  | `DELETE /api/v1/people/split-rules/{id}` | | sets `removed_at` |
  | `GET /api/v1/people` | | `[{personKey, label, owedToUser, since, family}]` |
  | `GET /api/v1/people/person?key=` | | balance, timeline `[{date, kind, amount, txnId}]`, rules |
  | `PUT /api/v1/people/person/settings` | `{personKey, family}` | |
  | `GET /api/v1/people/bills/{billId}/shares` | | `[{creditId, label, date, amount, fromRule}]` |
  | `PUT /api/v1/people/preferences` | `{ownUpiId}` | |
  `label` = `PayeeLabel.of(latest row for that key, "Unknown")`. Rules and entries on a key
  `identifiesOnePayee` rejects → 422.

- [ ] **Step 1: Failing tests:** `PeopleServiceTest.letGoRefilesUnderGifts`, `.settleCashClosesBalance`, `.splitRuleRefusesWeakKey`, `.linkDefaultsToCap`; `PeopleControllerIT.otherUsersTransactionIs404`, `.linkThenUndoRestoresFigures` (dashboard Rent before = after unlink).
- [ ] **Step 2: Run** `./mvnw test -Dtest=PeopleServiceTest` and the IT — Expected: FAIL.
- [ ] **Step 3: Implement** the service, controller and DTOs.
- [ ] **Step 4: Run** the same — Expected: PASS.
- [ ] **Step 5: Commit** `feat(transactions): people endpoints for shares, loans and rules`.

### Task 5: Suggestions and expected shares

**Files:**
- Create: `people/PeopleSuggestions.java`; Modify `PeopleController` (`GET /api/v1/people/suggestions`).
- Test: `people/PeopleSuggestionsTest.java`

**Interfaces:**
- Produces `GET /api/v1/people/suggestions` → `{items: [Suggestion], expected: [ExpectedShare]}`:
  ```java
  record Suggestion(String kind, String ackKey, UUID txnId, String personKey, String label,
                    BigDecimal amount, LocalDate date, List<BillOption> bills, BigDecimal owed) {}
  record BillOption(UUID billId, String category, String payeeLabel, LocalDate date, BigDecimal amount, BigDecimal remaining) {}
  record ExpectedShare(String personKey, String label, BigDecimal usualAmount, int usualDay, boolean late, UUID ruleId) {}
  ```
  `kind` ∈ `SHARE_CHECK` (spec §6 A), `LENT_CHECK` (B), `PAYBACK_CHECK` (C), `SETTLE` (E), `EVERY_MONTH` (F),
  `ALWAYS_PERSON` (G). D is the `SHARE_CHECK` "Not a share" branch on the client, not its own kind.
  `ackKey`: SHARE_CHECK/LENT_CHECK/PAYBACK_CHECK = txn id; SETTLE = `personKey|<newest entry id>`;
  EVERY_MONTH = `senderKey|billCategoryId`; ALWAYS_PERSON = person key. The server removes acknowledged items.
  Share words in the UPI note: `rent`, `share`, `split`, `trip`, `dinner`, `lunch`, `bill`.
  Ordering: SHARE_CHECK, PAYBACK_CHECK, EVERY_MONTH, ALWAYS_PERSON, LENT_CHECK, SETTLE; newest first within a kind; at most 5 items.

- [ ] **Step 1: Failing tests** (one per trigger and each boundary): `shareCheckOnSpendingCategory`, `shareCheckOnRepaymentWithNoEarlierPayment`, `shareCheckOnNoteWord`, `noShareCheckWhenLinked`, `billsListedBeforeThenAfter`, `lentCheckAfter30DaysOnly` (29 days → none, 30 → one), `noLentCheckForFamily`, `noLentCheckForRepeatingPayment`, `paybackCheckWhenOwed`, `paybackCheckForUnansweredPaymentWithin180Days` (181 → none), `settleAfter60Days` (59 → none), `settleSilencedUntilBalanceChanges`, `everyMonthNeedsAnotherMonth`, `acknowledgedItemsRemoved`, `expectedShareLateAfterUsualDay`, `quietForUserWithNoPersonRows`, `atMostFive`.
- [ ] **Step 2: Run** `./mvnw test -Dtest=PeopleSuggestionsTest` — Expected: FAIL.
- [ ] **Step 3: Implement** `PeopleSuggestions.build(PeopleLedger, rows, acknowledgements, familyKeys, LocalDate today)` and the endpoint.
- [ ] **Step 4: Run** the same — Expected: PASS.
- [ ] **Step 5: Commit** `feat(transactions): suggest shares, loans and settling up`.

### Task 6: Running-high average from the first month seen

**Files:** Modify `InsightsService.java` (mover average, around `int priorCount`). Test: `InsightsServiceTest`.

- [ ] **Step 1: Failing tests:** `moverAverageStartsAtCategorysFirstMonth` (Rent 0 in the first prior month, 9000 in the next three, 9000 now → not a mover); `moverWithNoPriorMonthsStaysQuiet` (unchanged behaviour); `occasionalCategoryStillAveragesItsZeroMonthsAfterFirstSeen` (seen in prior month 1, absent 2–4 → divisor 4).
- [ ] **Step 2: Run** `./mvnw test -Dtest=InsightsServiceTest` — Expected: the first FAIL.
- [ ] **Step 3: Implement** per category: divisor = number of `priorMonths` not earlier than that category's first month with spend in `txns` (min 1); no prior months at or after it → `pctChange` null.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `fix(analytics): a category's usual month starts when it first appears`.

### Task 7: Contract, full suite, PR A

- [ ] **Step 1:** `backend/scripts/generate-openapi-spec.sh` (throwaway postgres:16-alpine on 5432; delete stale `target/classes` migrations first); regenerate `generated-types.ts` in `frontend`, `admin`, `mobile`.
- [ ] **Step 2:** `./mvnw verify` (launch with `nohup`, log into `backend/target/`) — Expected: BUILD SUCCESS.
- [ ] **Step 3:** Self-review the diff for the Review Focus lines and for real values; push; open PR A.

---

## Part B — mobile (PR B, after PR A merges)

### Task 8: API client, keys and refresh

**Files:** Modify `mobile/src/api/endpoints.ts` (`peopleApi` mirroring Task 4/5 endpoints, types from `generated-types.ts`), `mobile/src/lib/invalidateFinancialData.ts` + its test, `mobile/src/lib/changeSync.ts`.

**Interfaces:**
- Produces query keys `['people']`, `['people','suggestions']`, `['people','person',key]`, `['people','bill',billId]` — all FINANCIAL (a write changes figures); every write invalidates them plus the financial keys.

- [ ] **Step 1:** Failing: `invalidateFinancialData.test` classifies the four keys; `changeSync` test lists them under transactions.
- [ ] **Step 2–4:** Run (Node 22 jest command above) → FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(mobile): people API client`.

### Task 9: Insights rows and sections

**Files:** Modify `mobile/src/lib/insightsFeed.ts` (`LookAtRow` gains `{kind:'people'; item: Suggestion}`; `buildToLookAt` puts people rows after `card-statement`), `mobile/src/components/insights/LookAtSection.tsx`, `ComingUpSection.tsx` (expected shares: "<label>'s share, around the <day>", late → "late"), `PaidRegularlySection.tsx` ("split with N" from rules), `InsightsQuestions.tsx` ("Who owes me?"), `mobile/src/lib/useInsightsLookAtCount.ts`.
Create: `mobile/src/components/insights/WithPeopleSection.tsx`.

Copy (exact, from the approved mockups): SHARE_CHECK "<amount> from <label> on <date>. You filed it under <category>." / "Is it their share of a bill you paid?" chips "Yes, pick the bill", "Not a share", "Got it"; LENT_CHECK "<amount> to <label> on <date>. Nothing back yet." / "What was it?" chips "Lent to <label>", "Paid for something", "Gift", "Got it"; PAYBACK_CHECK "<amount> from <label>." / "Paying back what you lent? <label> owes you <owed>." chips "Yes, paid back", "Something else"; SETTLE "<label> owes you <owed>, <days> days." chips "Ask <label>", "Paid in cash", "Let it go", "Keep waiting"; EVERY_MONTH "<label> sent a share in other months too. Link their <category> shares every month?" chips "Every month", "Just this one"; ALWAYS_PERSON chips "Always do this for <label>", "Ask each time" — "Always" calls the existing `inflowApi.setChoice(txnId, <built-in Paid back kind id>, 'SENDER')`, which is the repayment rule `PeopleLedger` reads. With people: headings "Owed to you", "You owe".

- [ ] **Step 1:** Failing tests per row kind (text, chip press calls the right `peopleApi` method, Got it acknowledges with the server's `ackKey`), `WithPeopleSection` (both headings, family never listed, tap navigates to Person), the new question scrolls to With people, the tab dot counts people rows.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(mobile): shares and loans in Insights`.

### Task 10: Bill picker, details sheet, person page

**Files:** Create `mobile/src/components/people/BillPickerSheet.tsx`, `mobile/src/screens/PersonScreen.tsx`; Modify `mobile/src/screens/TransactionDetailSheet.tsx`, `mobile/src/navigation/types.ts` (`Person: { personKey: string }`).

- Bill picker: the suggestion's `bills` in server order, first preselected; "Something else" opens the Ledger list filtered to expenses; after linking, if the response's suggestions contain an `EVERY_MONTH` for this sender, show it in place (screen 3 of the mockup).
- Details sheet: a bill with shares shows "Paid", "Your share", each share with Undo; any expense offers "Split this"; a payment to a person offers "Lent"; a person credit offers "Share of a bill", "Paid back", "Borrowed".
- "Ask <label>": when `ownUpiId` is unset, ask once and `PUT /preferences`; then share `upi://pay?pa=<ownUpiId>&am=<owed>&cu=INR` through the system share sheet. Nothing is sent by the app.
- Person page: balance, timeline, rules with Remove, Family switch.

- [ ] **Step 1:** Failing tests: picker preselects the first bill and links it; Undo on a share calls unlink; "Ask" without a UPI id prompts once, with one opens the share sheet with the exact URL; Family switch calls settings; Remove rule calls delete.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(mobile): pick the bill, see your share, a page per person`.

### Task 11: Full mobile verification, PR B

- [ ] **Step 1:** Full jest (Node 22), `tsc --noEmit`, `eslint --max-warnings 0`, the hygiene scripts — Expected: all clean.
- [ ] **Step 2:** Android emulator against production, read-only (no confirms on real data): the rows appear where the data says, the details sheet and the person page render. iOS only if Sid asks.
- [ ] **Step 3:** Push; open PR B.
