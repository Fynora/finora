# Card Amount Owed Prompt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (this repository forbids subagent execution and subagent verification -- see CLAUDE.md). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ask for a credit card's amount owed when no statement states it; hide and leave out of totals a card whose amount owed is not known; fix the admin coverage status and minus-sign placement.

**Architecture:** A server-side derived rule (`AmountOwedRule`) decides "not known" from columns that already exist. A shared `TypedBalance` component states a balance the way an account edit does; the import confirm uses it for the new optional `amountOwedToday`. The totals services filter unknown cards out and report how many. Clients read `amountOwedKnown` and send `amountOwedToday`.

**Tech Stack:** Spring Boot / JPA / Flyway Postgres (no migration), React + Vite + Vitest (web, admin-portal), React Native + Jest/RNTL (mobile).

**Spec:** `docs/superpowers/specs/2026-10-26-card-amount-owed-prompt-design.md`

## Global Constraints

- Card sign convention: `Account.balance` positive = owed (`AccountBalanceConvention`).
- Question copy: "How much do you owe on this card today?". Helper: "This statement doesn't show your balance. Your card app or latest bill has it." Skip: "Skip for now".
- Card copy: "Amount owed not known" with action "Add it". Totals line: "Leaves out 1 card whose amount owed isn't known." (plural: "N cards").
- Admin coverage chip for no statements: "No statements on file".
- Minus sign: U+2212 "−" before "₹".
- No Flyway migration. No change to any customer's data.
- Mobile ships with 1.1.0; never OTA to 1.0.0.
- Commit messages carry no AI co-author trailer.
- Node for web/mobile tests: `npx node@22` semantics (CI is Node 22).

## Review Focus

1. A rename-only account update on an unknown card must keep it unknown. `update()` must not mark typed when `balance` is null. Test in Task 2.
2. A stray `amountOwedToday` together with a closing balance: ignored, and the closing balance SETs the card. Test in Task 3.
3. `amountOwedToday` on a savings account: ignored, balance untouched. Test in Task 3.
4. Negative `amountOwedToday`: 400 at the boundary, nothing written. Test in Task 3.
5. The user's only import deleted: the card goes back to known (no imports), so no prompt for a ₹0 card. Test in Task 1.

---

## PR 1 -- backend (branch `worktree-card-amount-owed`)

### Task 1: `AmountOwedRule` and `AccountDto.amountOwedKnown`

**Files:**
- Create: `backend/src/main/java/com/finora/accounts/AmountOwedRule.java`
- Modify: `backend/src/main/java/com/finora/repository/StatementImportRepository.java` (new projection query)
- Modify: `backend/src/main/java/com/finora/accounts/AccountDto.java` (new trailing component `boolean amountOwedKnown`; existing `from` overloads pass `true`; new 9-arg `from`)
- Modify: `backend/src/main/java/com/finora/accounts/AccountService.java` (`listForUser`, `update` resolve it)
- Test: `backend/src/test/java/com/finora/accounts/AmountOwedRuleTest.java`, plus an IT asserting `amountOwedKnown` over `GET /api/v1/accounts`.

**Interfaces:**
- Produces: `AmountOwedRule#unknownCardIds(UUID userId, Collection<Account> accounts): Set<UUID>`; `AmountOwedRule#isUnknown(Account account): boolean`.
- Produces: `StatementImportRepository#findImportModesForUser(UUID userId): List<ImportMode>` where `ImportMode { UUID getAccountId(); StatementImport.BalanceApplicationMode getBalanceApplicationMode(); }` (live rows only, by `@SQLRestriction`).

Rule (spec, verbatim): CREDIT_CARD, `lastAbsoluteSetStatementId == null`, `balanceTypedAt == null`, `balanceBaselineDate == null`, at least one live import, none `ABSOLUTE` or `UNKNOWN_LEGACY`. Short-circuit: no candidate card, no query.

- [ ] Step 1: Unit tests (mock repository): `unknownWhenCardHasOnlyAdditiveImports`, `knownWithAnchor`, `knownWithTypedAt`, `knownWithBaseline`, `knownWithNoImports`, `knownWithAbsoluteImport`, `knownWithLegacyImport`, `savingsNeverUnknown`, `noQueryWhenNoCandidateCard` (verify zero repository calls).
- [ ] Step 2: Run, expect compile failure.
- [ ] Step 3: Implement the rule and the repository query.
- [ ] Step 4: IT: an ADDITIVE card import with no closing balance, then `GET /api/v1/accounts` gives `amountOwedKnown=false` for the card and `true` for a savings account. Delete that statement (`StatementImportService.delete`), and it gives `true` (Review Focus 5).
- [ ] Step 5: Wire `listForUser` (one `findImportModesForUser` call per list) and `update`.
- [ ] Step 6: Run the tests, expect pass. Commit `feat(backend): derive whether a card's amount owed is known`.

### Task 2: `TypedBalance`, shared by account edit

**Files:**
- Create: `backend/src/main/java/com/finora/accounts/TypedBalance.java`
- Modify: `backend/src/main/java/com/finora/accounts/AccountService.java` (move `markBalanceTyped` and `rebaseDuplicateMarks` into it; `create` keeps its non-zero rule and calls `TypedBalance#markTyped`; `update` calls `TypedBalance#state`)
- Test: existing `AccountService*` tests and ITs, plus `TypedBalanceTest`.

**Interfaces:**
- Produces: `TypedBalance#state(Account account, BigDecimal balance): void`. It sets the balance, clears `lastAbsoluteSetStatementId`, stamps `balanceTypedAt=now` and `balanceBaselineDate=today-1` in the user's zone, and rebases duplicate marks. It does not save the account.
- Produces: `TypedBalance#markTyped(Account account): void`, which only stamps.

- [ ] Step 1: `TypedBalanceTest`: `stateSetsBalanceClearsAnchorStampsTypedAndBaseline`, with the baseline equal to yesterday in an `Asia/Kolkata` user zone and in a `America/Los_Angeles` user zone.
- [ ] Step 2: Test `renameOnlyUpdateKeepsUnknown` (Review Focus 1). An update with `balance=null` leaves `balanceTypedAt` null, and `amountOwedKnown` stays false.
- [ ] Step 3: Refactor with no behaviour change. Run the `AccountService` unit tests and the ITs that cover a balance edit (`grep -l "markBalanceTyped\|balanceTypedAt" backend/src/test`). Expect pass.
- [ ] Step 4: Commit `refactor(backend): one TypedBalance for every stated balance`.

### Task 3: `amountOwedToday` at import confirm

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/ImportDto.java`. Add a trailing `@DecimalMin("0") @Digits(integer=12, fraction=2) BigDecimal amountOwedToday` to `ConfirmRequest` and `SectionConfirm`. Every existing constructor and `with*` copies pass it through, or pass null.
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java`. `confirmMultiSection` maps `SectionConfirm.amountOwedToday` into each per-section `ConfirmRequest`. `persistSection` applies it after the ADDITIVE block.
- Modify: `ImportController` if `@Valid` is missing on either confirm binding (check; add if absent).
- Test: `backend/src/test/java/com/finora/imports/AmountOwedAtConfirmIT.java`

Apply rule, in `persistSection`, last balance step: re-fetch the account (the BaseEntity `@Version` trap). Apply only if `amountOwedToday != null`, `statementClosingBalance == null` and the type is `CREDIT_CARD`. Then call `TypedBalance#state`, save, and audit `ACCOUNT_UPDATED` with `previousBalance`, `newBalance`, `source=IMPORT_CONFIRM` and `actorId=userId`.

- [ ] Step 1: Write the IT, with exact balances asserted on `Account.balance`:
  - `newCardNoClosingWithAmountSetsBalanceAndKnown`: a CSV with 3 rows (spend 1,000, spend 500, payment 2,000), new account with no opening, `amountOwedToday=12,345.67`. Balance is `12345.67`, `balanceTypedAt` is not null, and `amountOwedKnown=true`.
  - `sameWithoutAmountIsUnknown`: balance `-500.00` (net), `amountOwedKnown=false`.
  - `deletingARowAfterTypedKeepsBalance`: after the first case, delete the 1,000 row through `TransactionService`. Balance stays `12345.67`.
  - `refreshAfterTypedKeepsBalance`: after the first case, a statement refresh that adds one row and removes one. Use the existing refresh IT harness (`grep -l "StatementRefreshService" backend/src/test`). Balance stays `12345.67`.
  - `laterStatementMovesOnlyByNewRows`: after the first case, confirm a second statement on the same card with one row dated yesterday-minus-30 days and one dated today (spend 250). Balance is `12595.67`, and the older row is covered.
  - `closingBalanceWinsOverStrayAmount` (Review Focus 2): a statement with closing `8,000.00` plus `amountOwedToday=1`. Balance is `8000.00` and the anchor is set.
  - `savingsIgnoresAmount` (Review Focus 3).
  - `negativeAmountRejected` (Review Focus 4): 400, no rows, balance unchanged.
  - `multiSectionCarriesAmountPerSection`: a two-section confirm where only the card section has an amount. The card balance equals the amount, and the savings section is unchanged.
- [ ] Step 2: Run, expect failures.
- [ ] Step 3: Implement.
- [ ] Step 4: Run the IT plus `ImportService*`, `StatementRefresh*` and `StatementImportService*` tests. Expect pass.
- [ ] Step 5: Commit `feat(backend): take the amount owed on a card at import confirm`.

### Task 4: Leave unknown cards out of totals

**Files:**
- Modify: `backend/src/main/java/com/finora/service/DashboardService.java`. Net worth, `totalLiabilities` (`sumBalances(..., CREDIT_CARD)`), utilisation (around line 646) and the due-payment notification (around line 753) all use the accounts minus unknown cards.
- Modify: `backend/src/main/java/com/finora/service/DashboardRangeService.java` (`netWorthOf`).
- Modify: `backend/src/main/java/com/finora/service/NetWorthService.java` (`current`, `saveSnapshotForToday`).
- Modify DTOs: add `int cardsWithUnknownAmountOwed` to `DashboardSummaryDto`, `DashboardRangeSummaryDto` and `NetWorthDto`, trailing, with legacy constructors defaulting to 0.
- Test: `backend/src/test/java/com/finora/service/UnknownAmountOwedTotalsIT.java`

- [ ] Step 1: IT. A user with savings 10,000 and an unknown card at net -500 (from Task 3's second case). The dashboard summary has `netWorth=10000.00`, `totalLiabilities=0.00` and `cardsWithUnknownAmountOwed=1`. Net worth `current` and today's snapshot both read `10000.00`. After `PUT /accounts/{id}` with balance 3,000: `netWorth=7000.00`, count 0.
- [ ] Step 2: Run, expect failures. Step 3: implement. Step 4: run with `DashboardService*`, `NetWorth*` and `DashboardRange*` tests; expect pass.
- [ ] Step 5: Commit `feat(backend): leave cards with no known amount owed out of totals`.

### Task 5: Coverage reads unknown with no statements

**Files:** Modify `backend/src/main/java/com/finora/imports/StatementCoverageAnalyzer.java`: `coverageStatus()` returns `"NO_STATEMENTS"` when `segments` is empty. Test in the existing `StatementCoverageAnalyzerTest`.

- [ ] Step 1: Test `emptyPeriodsReportNoStatements`. Step 2: fails. Step 3: implement. Step 4: passes. Step 5: commit `fix(backend): coverage with no statements is not COMPLETE`.

### Task 6: Contract and full verification

- [ ] Step 1: `backend/scripts/generate-openapi-spec.sh`; `npm run generate:types` in `frontend`, `mobile` and `admin-portal`.
- [ ] Step 2: Type-check all three clients (`npx tsc --noEmit` in each). They must pass with the new optional fields.
- [ ] Step 3: `mvn -q verify` (full, ITs included) in the background. Expect green. Read any failure before acting.
- [ ] Step 4: Commit `chore: regenerate OpenAPI spec and client types`. Push and open PR 1.

## PR 2 -- web + admin (branch from PR 1's tip; rebase onto `origin/main` once PR 1 is squash-merged)

### Task 7: Web review question

**Files:**
- Create: `frontend/src/lib/amountOwed.ts`. `shouldAskAmountOwed(input: { accountType: string | null; closingBalance: number | null; existing: { accountType: string; balanceStated?: boolean } | null; newOpeningBalance: string | null }): boolean`. True only for a card with a null closing balance, where the existing card has `balanceStated === false` (a hand-made card at ₹0 included -- see the spec), or it is a new card with a blank opening balance.
- Create: `frontend/src/components/import/AmountOwedQuestion.tsx`. Props: `{ value: string; onChange(v: string): void; skipped: boolean; onSkip(): void; onUndoSkip(): void }`. Uses the constraint copy.
- Modify: `frontend/src/pages/Import.tsx`. Show the question on the single-account review and per section on the multi-account review. Send `amountOwedToday` (number, or omitted when blank or skipped) in both confirm payloads. On the result screen, when the confirm response's `account.amountOwedKnown` is false, show "Amount owed not known" instead of its balance.
- Test: `frontend/src/lib/amountOwed.test.ts` (the four spec cases plus savings), and `Import.test.tsx` cases: shown and sent; skipped means not sent; not shown with a closing balance; not shown for a card with a stated figure; shown for a card made by hand at ₹0 (`balanceStated` false); the result screen hides a summed figure.

- [ ] Steps: failing tests, run, implement, run (`npx vitest run src/lib/amountOwed.test.ts src/pages/Import.test.tsx`), lint and tsc, commit `feat(web): ask what's owed on a card when the statement doesn't say`.

### Task 8: Web card state, "Add it" and totals line

**Files:**
- Create: `frontend/src/components/accounts/AddAmountOwedDialog.tsx`. Props: `{ account: Account; open: boolean; onClose(): void }`. Calls `accountsApi.update(account.id, { name, accountType, balance })` and invalidates the accounts, dashboard and net worth queries.
- Modify: `frontend/src/pages/Setup.tsx` (lines ~258 and ~301) and `frontend/src/pages/Dashboard.tsx` (~1370). When `amountOwedKnown === false`, render "Amount owed not known" and an "Add it" button instead of `fmt(a.balance)`.
- Modify: `Dashboard.tsx`. Under net worth or total liabilities, show the totals line when `cardsWithUnknownAmountOwed > 0`.
- Test: component tests for the dialog payload and the card state, and Dashboard showing the line for 1 and 2 cards and hiding it for 0.

- [ ] Steps: TDD as above; commit `feat(web): show when a card's amount owed isn't known`.

### Task 9: Admin fixes

**Files:**
- Create: `admin-portal/src/lib/formatInr.ts`. `formatInr(n: number): string` returns "−₹1,00,396.53" for negatives (`en-IN`, 2 decimals when present).
- Modify: `admin-portal/src/pages/user-detail/AccountsSection.tsx`. The balance (~332) and the gap delta (~47) use `formatInr`. With `coverageStatus === 'NO_STATEMENTS'`, a neutral chip "No statements on file" and no days line. A card with `amountOwedKnown === false` gets a tag "amount owed not known".
- Test: `formatInr.test.ts` (-100396.53, 0, 1234.5) and `AccountsSection.test.tsx` (the chip and the tag).

- [ ] Steps: TDD; then `npm run lint`, `tsc` and `vitest run` in `frontend` and `admin-portal`; commit; push; open PR 2.

## PR 3 -- mobile (same base rule as PR 2)

### Task 10: Mobile review question

**Files:**
- Create: `mobile/src/lib/amountOwed.ts`, the same signature as the web helper.
- Create: `mobile/src/screens/import/AmountOwedQuestion.tsx`, the same props as web.
- Modify: `mobile/src/screens/import/ImportScreen.tsx` and `mobile/src/lib/importPayload.ts`. Show the question; send `amountOwedToday` in the single and multi payloads; the result screen hides a summed figure the same way as web.
- Test: `amountOwed.test.ts`, `importPayload.test.ts` and `ImportScreen.test.tsx` (the same cases as web, including the hand-made ₹0 card and the result screen).

### Task 11: Mobile card state, "Add it" and totals line

**Files:**
- Create: `mobile/src/screens/AddAmountOwedSheet.tsx`, with the same contract as the web dialog.
- Modify: `mobile/src/screens/AccountsScreen.tsx` (~195), `mobile/src/components/dashboard/AccountsCard.tsx` (~44) and the net worth or liabilities KPI (`useDashboardKpis.ts` consumer) for the totals line.
- Test: RNTL tests for each, using `not.toBeOnTheScreen` and not `waitFor(toBeNull)`.

- [ ] Steps: TDD; `npx jest` for the touched files, then the full mobile suite, lint and tsc; commit; push; open PR 3 marked "ships in 1.1.0, no OTA".

## Done

- Each PR: build, lint, type-check and tests pass for what it touches, run by me with the output read.
- The repository is public, so PRs open without the full-ci label. Re-check `gh repo view --json visibility` before calling CI evidence.
- Hand-over note in the shared notes folder: what the affected customer will see, and that nothing was changed for them.
