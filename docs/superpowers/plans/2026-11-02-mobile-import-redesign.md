# Mobile Import Redesign (Idea B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the mobile Import screen's three steps as Idea B: per-account statement months on Add, questions-first on Check with a pinned Import bar, and "{Month} in Fynora" on Done.

**Architecture:** Every rule goes into a pure function in `mobile/src/lib/` with its own tests. Small components in `mobile/src/screens/import/` render those rules. `ImportScreen.tsx` keeps all of its state and handlers and only swaps what each step renders. No server change: coverage comes from `GET /accounts/{id}/coverage`, and Done uses `GET /insights/look-at`.

**Tech Stack:** React Native 0.8x / Expo SDK 57, TypeScript, React Query, jest + React Native Testing Library.

**Spec:** `docs/superpowers/specs/2026-11-02-mobile-import-redesign-design.md`

## Global Constraints

- Mobile only (`mobile/`). No backend or web change.
- Ships in the next store binary. Never OTA.
- Theme tokens only (`mobile/src/theme`): Manrope headings, Inter body. Income uses `successInk` and expenses use `dangerInk`.
- Light and dark mode. Every new control has an `accessibilityLabel`. jest's default font scale is 2.
- `canConfirmImport` (`lib/importGate.ts`) and its tests are not changed. The Import button is enabled exactly when it returns true.
- No real names, account numbers or amounts in code, tests or commits. Use "Sample Bank", "••0001" and similar.
- Commits use scope `mobile` (or `docs`), with no `Co-Authored-By` or any other AI trailer.
- Test command, run from `mobile/` (`T` below):
  `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 node_modules/.bin/jest`.
  Typecheck: `npx -y node@22 node_modules/.bin/tsc --noEmit`. Lint: `npx -y node@22 node_modules/.bin/expo lint --max-warnings 0`.
- Read `mobile/AGENTS.md`: check the versioned Expo docs before using any Expo API.
- Rulings on what the spec left open:
  - **"People" rows** (other people's shares and loans) are left off Done. They need their own action buttons, which live in `PeopleLookAtRow`.
  - **"Choose an account"** is added to the bar's blocking reasons, because `canConfirmImport` also refuses an existing-account choice with no account picked. Order: duplicates, account not chosen, bank missing, amount owed, free plan, no rows.
  - **Skipped and duplicate counts** on Done read "{n} skipped" and "{n} duplicate(s)", joined by ", ", each shown only when non-zero. Whether duplicates are a subset of skipped is not established, so they are never added together.
  - **Each "Your statements" card** shows only the account name while its coverage loads.

## Review Focus

1. **Re-import arrival** (`route.params.reimport`): the Check step shows "File into: {name}" without "Change", and the bar never says "Choose an account". Tested in Task 5.
2. **Every row is a possible duplicate, all answered "Skip this row"**: included count is 0, the bar says "Choose at least one transaction to import" and the button is disabled. Tested in Task 5.
3. **"Apply to similar" answers several duplicates at once**: every answered card leaves "need you" in one render. Tested in Task 5.
4. **Month dates at the edges**: latest end 31 Jan plus one month clamps to the end of February, and a December end rolls into January of the next year. Tested in Task 1.
5. **The pinned bar must not cover the last row or Cancel**: the list's bottom padding includes the bar's measured height. Checked on the simulator in Task 8 at large text, since jest can't measure layout.

---

### Task 1: Statement-month rule

**Files:**
- Create: `mobile/src/lib/statementMonths.ts`
- Test: `mobile/src/lib/statementMonths.test.ts`

**Interfaces:**
- Consumes: the `AccountCoverage` shape from Task 2. Declare it here as an input type with only the fields used: `{ segments: { periodStart: string; periodEnd: string }[]; gaps: { gapStart: string; gapEnd: string }[] }`. Dates are `YYYY-MM-DD`.
- Produces:
  - `type MonthState = 'covered' | 'missing' | 'next'`
  - `interface MonthChip { month: string /* YYYY-MM */; state: MonthState }`
  - `type MonthsLine = { kind: 'next'; month: string } | { kind: 'up-to-date' } | { kind: 'missing'; months: string[] /* newest first */ }`
  - `statementMonths(coverage: CoverageInput, today: string): { chips: MonthChip[]; line: MonthsLine }`
  - `monthsLineText(line: MonthsLine): string`
  - `qualifiesForMonths(a: Pick<Account, 'primarySource' | 'statementsCount'>): boolean`
  - `monthName(month: string, style: 'short' | 'long'): string` ("Oct" / "October", no year)

- [ ] **Step 1: Write the failing tests.** One `it` per spec case. `today` is `'2026-11-02'` unless stated.
  - `monthly savings Jun–Sep → covered Jun..Sep, next Oct`: segments for each month from 1 Jun to 30 Sep, no gaps. Expect chips `['2026-06','covered'] … ['2026-09','covered'], ['2026-10','next']` and `monthsLineText(line) === "Next: October's statement"`.
  - `August gap → missing`: same, with August's segment removed and gap `2026-08-01..2026-08-31`. Expect Aug `missing`, and the line `"August's statement is missing"`.
  - `card cycles, one absent cycle → one missing chip`: segments `06-15..07-14`, `07-15..08-14`, `09-15..10-14`; gap `08-15..09-14`. Expect `Jul covered, Aug covered, Sep missing, Oct covered`, no `next` chip, and the line `"September's statement is missing"`.
  - `one long statement → every month covered`: segment `06-01..09-30`. Expect Jun, Jul, Aug and Sep covered, then Oct next.
  - `latest end + 1 month after today → up to date`: card segment ending `2026-10-14`. Expect no `next` chip and the line `"Up to date"`.
  - `two missing → count line`: gaps covering Jul and Aug. The line is `"2 statements are missing"`.
  - `at most five past chips`: monthly segments Jan–Sep. Expect chips May…Sep, then Oct next (6 chips).
  - `31 Jan end clamps`: segment `2027-01-01..2027-01-31`, today `2027-02-28`. Expect next `2027-02`. Today `2027-02-27` gives up to date.
  - `December rolls the year`: segment `2026-12-01..2026-12-31`, today `2027-01-31`. Expect next `2027-01`.
  - `no segments → empty`: `{ chips: [], line: { kind: 'up-to-date' } }`.
  - `qualifiesForMonths`: `MANUAL`+3 → true; `MANUAL`+0 → false; `ACCOUNT_AGGREGATOR`+3 → false.

- [ ] **Step 2: Run them and watch them fail.** Run: `T src/lib/statementMonths.test.ts`. Expected: FAIL, cannot find module `./statementMonths`.

- [ ] **Step 3: Implement** the rules exactly as the spec's "Month chips — exact rule" states them:
  - "Covered" means a segment's `periodEnd` falls in the month, or the segment contains every day of it.
  - "Missing" means not covered, a gap overlaps the month, and the month lies between the earliest and latest segment-end months.
  - "Next" is the month after the latest end. Show it only when the latest end plus one calendar month (day clamped to the month's length) is on or before `today`.
  - Keep the last five covered or missing months.
  - Work in `YYYY-MM` / `YYYY-MM-DD` strings. Never use `new Date(string)`: it parses as UTC and shifts days in IST. Use `fromLocalDateString` / `toLocalDateString` from `lib/format.ts`.

- [ ] **Step 4: Run and pass.** Run: `T src/lib/statementMonths.test.ts`. Expected: PASS, 11 tests.

- [ ] **Step 5: Commit.** `git add mobile/src/lib/statementMonths.ts mobile/src/lib/statementMonths.test.ts && git commit -m "feat(mobile): statement-month rule for the Import screen"`

---

### Task 2: Coverage client and the "Your statements" card

**Files:**
- Modify: `mobile/src/api/endpoints.ts` (next to `accountsApi`, line ~181)
- Modify: `mobile/src/lib/invalidateFinancialData.ts` (`FINANCIAL_QUERY_KEYS`)
- Create: `mobile/src/screens/import/YourStatements.tsx`
- Test: `mobile/src/screens/import/YourStatements.test.tsx`, `mobile/src/lib/invalidateFinancialData.test.ts` (add one case)

**Interfaces:**
- Consumes: Task 1 (`statementMonths`, `monthsLineText`, `qualifiesForMonths`, `monthName`).
- Produces:
  - `interface AccountCoverage`, mirroring `CoverageDto` field for field, with a `FieldsOf<AccountCoverage, GeneratedSchemas['CoverageDto']>` guard. Use the same pattern as `_aiInsightShapesMatchTheServer`.
  - `accountsApi.coverage(id: string): Promise<AccountCoverage>` → `GET /accounts/${id}/coverage`.
  - Query key `['account-coverage', accountId]`, with `'account-coverage'` added to `FINANCIAL_QUERY_KEYS`.
  - `<YourStatements accounts={Account[]} today={string} />`. It renders `null` when no account qualifies; the caller shows the new-user lead instead.

- [ ] **Step 1: Write the failing tests** in `YourStatements.test.tsx`. Mock `accountsApi.coverage` per id.
  - `lists qualifying accounts, newest import first`: a `MANUAL` account with 2 statements, `lastImportedAt` older; a second `MANUAL` account, newer; an `ACCOUNT_AGGREGATOR` account; a `MANUAL` account with 0 statements. Expect exactly two cards, the newer one first, and `getByText('Your statements')`.
  - `shows chips and the next line`: coverage as in Task 1's savings case. Expect chips with the labels `Jun Jul Aug Sep Oct` and the text `"Next: October's statement"`. Each chip's `accessibilityLabel` is `"June, in Fynora"` / `"October, next"` / `"August, missing"`.
  - `failed coverage → "Couldn't load months"` on that card only; the other card still shows its chips.
  - `while loading → name only`: an unresolved promise shows the name and no chips.
  - `nothing qualifies → renders nothing`: `toJSON()` is `null`.
  - Add a case to `invalidateFinancialData.test.ts`: `'account-coverage'` is invalidated.

- [ ] **Step 2: Run them and watch them fail.** Run: `T src/screens/import/YourStatements.test.tsx src/lib/invalidateFinancialData.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement** with one `useQueries` call over qualifying accounts, each with `retry: false`. Chip colours:
  - covered: `successBg` with `successInk`;
  - missing: a dashed `warning` border with `warningInk`;
  - next: a dashed `primary` border with `ink`.

  The line uses `warningInk` when `kind === 'missing'`, otherwise `muted`. Card title: `account.name`, plus ` ${accountNumberMasked}` when that optional field is set (`Account.accountNumberMasked?: string | null`). Sort by `lastImportedAt`, newest first, with nulls last.

- [ ] **Step 4: Run and pass.** Same command. Expected: PASS. Also run `T src/test/queryKeyShapes.test.ts`; expected PASS (no reshaping in the `queryFn`).

- [ ] **Step 5: Commit.** `feat(mobile): coverage client and Your statements card`

---

### Task 3: Add-a-statement step

**Files:**
- Modify: `mobile/src/screens/import/ImportScreen.tsx` (upload step, lines ~1095–1422)
- Create: `mobile/src/screens/import/FoldLine.tsx`. A tap-to-open row: a chevron, a label and children shown when open. `accessibilityRole="button"`, `accessibilityState={{ expanded }}`.
- Test: `mobile/src/screens/import/ImportScreen.test.tsx`

**Interfaces:**
- Consumes: `<YourStatements>` (Task 2). `accountsQ.data` already exists in `ImportScreen` (line ~361).
- Produces: `<FoldLine label: string; children; testID?: string>`, reused nowhere else.

- [ ] **Step 1: Write the failing tests** in a new `describe('upload step, idea B')`:
  - `shows Your statements when an account qualifies`: the accounts mock returns a `MANUAL` account with 2 statements, and coverage is mocked. Expect `findByText('Your statements')`; `queryByTestId('first-ask')` is null.
  - `shows the first-statement lead when none qualifies`: expect `getByTestId('first-ask')` and `"No bank statement to hand? A credit card statement works too."`
  - `file notes and handling are folded`: `queryByText(/scanned or photographed PDF/)` is null. Pressing `"Which files work?"` shows it. Pressing `"What happens to my statement?"` renders `StatementHandlingPanel` (assert on one line it already renders in its own test).
  - `other ways line`: pressing `"from your computer"` opens `SendFromComputerSheet` (only when `asyncAvailable`, as today). Pressing `"from your email"` shows `"Open the email, tap the PDF, then Share → Fynora."`
  - The tests for Ready to review, waiting for a computer file, the progress card, the password panel and failed imports are not edited, and must still pass.

- [ ] **Step 2: Run them and watch them fail.** Run: `T src/screens/import/ImportScreen.test.tsx -t "upload step, idea B"`. Expected: FAIL.

- [ ] **Step 3: Implement** in the order of the spec's Step 1 list:
  - The card titled "Import a statement" becomes: `YourStatements` or the lead, then `UploadProgressPanel` with "Choose a file", then the "Other ways" line, then two `FoldLine`s.
  - The password branch (`showPasswordPanel`) replaces the picker exactly as today.
  - `today` is `toLocalDateString(new Date())`.

- [ ] **Step 4: Run and pass.** Run: `T src/screens/import/ImportScreen.test.tsx`. Expected: PASS for the whole file. If an old test asserted text that moved into a fold, open the fold in that test. Never delete an assertion.

- [ ] **Step 5: Commit.** `feat(mobile): Import add step shows your statement months`

---

### Task 4: Check-step rules

**Files:**
- Create: `mobile/src/lib/importQuestions.ts`
- Test: `mobile/src/lib/importQuestions.test.ts`

**Interfaces:**
- Consumes: `isUnderReview`, `isUnconfirmedGuess`, `DuplicateDecision` (`lib/importReview.ts`); `FlaggedRows` (`components/verificationFlags.ts`); `ImportGateState` (`lib/importGate.ts`).
- Produces:
  - `questionRows(rows: StagedRow[], decisions: DuplicateDecision[], flagged: FlaggedRows, dismissed: ReadonlySet<number>): number[]`. Indexes in statement order: under review and `'unresolved'`, plus indexes in `flagged.balance`/`flagged.description` that are not in `dismissed`. Each index appears once.
  - `undismissedFlagCount(flagged: FlaggedRows, dismissed: ReadonlySet<number>): number`.
  - `categoryGuessCount(rows: StagedRow[], included: boolean[]): number`.
  - `fileIntoCollapsed(s: { isReimport: boolean; accountChoice: 'existing' | 'new'; selectedAccountId: string; amountOwedInvalid: boolean; newAccountBankMissing: boolean }): boolean`.
  - `importBarMessage(gate: ImportGateState, flagsToCheck: number): { kind: 'blocked' | 'check'; text: string } | null`.

- [ ] **Step 1: Write the failing tests**, with the exact copy:
  - `questionRows`:
    - an unresolved duplicate at 3 and a description flag at 1 give `[1, 3]`;
    - a decided duplicate is excluded;
    - a dismissed flag is excluded;
    - a row both flagged and unresolved appears once.
  - `categoryGuessCount`: counts only included rows with `categorySource` `'default'` or `'structural_p2p'`.
  - `fileIntoCollapsed`:
    - true for an existing account with a selected id and nothing invalid;
    - true for re-import;
    - false for `'new'`, for an empty selection, for `amountOwedInvalid`, and for `newAccountBankMissing`.
  - `importBarMessage`, one case per reason, in this order:
    1. outstanding 2 → `{ blocked, "Answer 2 questions to import" }`; outstanding 1 → `"Answer 1 question to import"`;
    2. existing account with no selection, not re-import → `"Choose an account to import into"`;
    3. bank missing → `"Choose the new account's bank to import"`;
    4. amount owed invalid → `"Fix the amount owed to import"`;
    5. free plan blocks, not re-import → `"This statement is over the Free plan's one-month limit"`;
    6. includedCount 0 → `"Choose at least one transaction to import"`.
  - `importBarMessage`, other cases:
    - nothing blocks, flags 2 → `{ check, "2 rows to check" }`; flags 1 → `"1 row to check"`;
    - nothing blocks, flags 0 → `null`;
    - re-import with an empty `selectedAccountId` → not "Choose an account".
  - Parity: for all 256 gate states from every combination of `includedCount ∈ {0, 3}`, `outstanding ∈ {0, 1}`, `isReimport`, `accountChoice ∈ {'existing', 'new'}`, `selectedAccountId ∈ {'', 'a1'}`, `freePlanLimitBlocks`, `amountOwedInvalid` and `newAccountBankMissing`, `importBarMessage(gate, 0)?.kind === 'blocked'` exactly when `!canConfirmImport(gate)`.

- [ ] **Step 2: Run them and watch them fail.** Run: `T src/lib/importQuestions.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement.** `importBarMessage` checks the reasons in the order listed above, mirroring `canConfirmImport`'s conditions.

- [ ] **Step 4: Run and pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `feat(mobile): rules for Import questions and the pinned bar`

---

### Task 5: Check step

**Files:**
- Create: `mobile/src/screens/import/ImportBar.tsx`: pinned bar, `{ message, buttonLabel, disabled, loading, onImport, onShowMe, onLayoutHeight }`.
- Create: `mobile/src/screens/import/ReviewSummary.tsx`: account name, masked number, period, `{n} transactions`, In and Out totals.
- Modify: `mobile/src/screens/import/ImportScreen.tsx` (review step, lines ~1496–1909)
- Test: `ImportBar.test.tsx`, `ReviewSummary.test.tsx`, `ImportScreen.test.tsx`

**Interfaces:**
- Consumes: Task 4 (all five functions); the existing `StagedRowCard` props, unchanged.
- Produces: new state in `ImportScreen`:
  - `dismissedFlags: Set<number>` (reset in `resetToUpload` and on new staging);
  - `showAllRows: boolean` (default false);
  - `fileIntoOpen: boolean` (default false);
  - `barHeight: number`.

- [ ] **Step 1: Write the failing tests.**
  - `ReviewSummary.test.tsx`:
    - In is the sum of included `INCOME` rows; Out is the sum of included `EXPENSE` rows;
    - excluded rows don't count;
    - the In text uses `successInk` and the Out text uses `dangerInk` (assert the `style` color).
  - `ImportBar.test.tsx`:
    - renders the message text;
    - `"Show me"` appears only for `kind: 'check'` and calls `onShowMe`;
    - the button is disabled when `disabled`;
    - `onLayoutHeight` receives the layout height.
  - `ImportScreen.test.tsx`, new `describe('check step, idea B')`:
    - `"need you" holds only unresolved duplicates and flagged rows`: stage 5 rows with one duplicate and one description flag. Expect `"2 need you"`, two row cards before "See all", and `"3 look fine"`.
    - `answering a duplicate removes its card`: press `"Skip this row"` → `"1 need you"`.
    - `"Apply to similar" clears several cards in one press` (Review Focus 3): two similar duplicates → one press → `queryByText(/need you/)` is null.
    - `"Looks right" hides a flagged row from "need you" only`: after the press, `"See all"` still shows its `"Check description"` badge.
    - `See all shows every row`: before, `StagedRowCard` count equals "need you" count; after, it is ≥ rows.length.
    - `guesses line`: two included rows with `categorySource: 'default'` → `"2 categories are guesses — sort them after import"`; no guesses → absent.
    - `File into collapses for a matched existing account`: shows `"File into: Sample Bank Savings"` and `"Change"`; the `"Opening balance"` input is absent; pressing `"Change"` shows the existing choice chips.
    - `File into stays open for a new account`: the `"Account name"` input is present.
    - `re-import shows no Change` (Review Focus 1): `"Change"` is absent, and the bar never says `"Choose an account to import into"`.
    - `all duplicates skipped → nothing to import` (Review Focus 2): the bar says `"Choose at least one transaction to import"` and the Import button is disabled.
    - `bar names the outstanding question`: `"Answer 1 question to import"`, and the button is disabled.
    - Every existing review-step test keeps its assertions. Where it presses a row that is now folded, press `"See all"` first. Where it asserted `"Detected"`, assert the summary's account name instead.

- [ ] **Step 2: Run them and watch them fail.** Run: `T src/screens/import/ImportBar.test.tsx src/screens/import/ReviewSummary.test.tsx src/screens/import/ImportScreen.test.tsx -t "check step, idea B"`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - `ListHeaderComponent` order follows the spec's Step 2 list: title, review promise, `ReviewSummary`, `VerificationPanel`, free-plan notice, previous-import notice, File into (collapsed line or today's card), "need you" cards, "look fine" card, guesses line.
  - The "need you" cards render `StagedRowCard` with today's props. Flagged rows also get a `Button variant="link" label="Looks right"`.
  - `FlatList data={showAllRows ? rows : []}`.
  - `ListFooterComponent`: unparseable rows, today's two gate notices, and Cancel. The Import `Button` moves into `ImportBar`, which is absolutely positioned at `bottom: tabBar + spacing.sm`.
  - The list's bottom padding is `padForTabBar(..., tabBar + barHeight + spacing.md)`.
  - "Show me" uses `listRef.scrollToOffset` to the "need you" section's `onLayout` y.

- [ ] **Step 4: Run and pass.** Run: `T src/screens/import`. Expected: PASS for the whole folder.

- [ ] **Step 5: Commit.** `feat(mobile): Import check step asks only what needs an answer`

---

### Task 6: Insights month link and Done lines

**Files:**
- Modify: `mobile/src/navigation/types.ts`: `Insights: { month?: string; nonce?: number } | undefined;`
- Modify: `mobile/src/screens/InsightsScreen.tsx`: read `useRoute().params`; `useEffect` on `params?.nonce` → `setMonth(params.month)` when `month` is set.
- Modify: `mobile/src/lib/insightsFeed.ts`: move `cardPhrase` here from `components/insights/LookAtSection.tsx` and export it. `LookAtSection` imports it; its own tests must still pass unchanged.
- Create: `mobile/src/lib/importDone.ts`
- Test: `mobile/src/lib/importDone.test.ts`, `mobile/src/screens/InsightsScreen.test.tsx` (one case)

**Interfaces:**
- Produces:
  - `statementMonthOf(periodEnd: string | null): string | null` (`'2026-09-30'` → `'2026-09'`).
  - `doneLines(rows: LookAtRow[]): { key: string; kind: LookAtRow['kind']; title: string; detail: string }[]`. Skips `people` rows and keeps at most 3, in the order given.
  - `doneCountsLine(skipped: number, duplicates: number): string | null`.

- [ ] **Step 1: Write the failing tests** with the exact copy:
  - `card-statement` (total 4200, cardName "Sample Card", last4 "0002", month "2026-09") → title `"Card statement missing for September 2026"`, detail `"You paid ₹4,200 toward your Sample Card ending 0002"`.
  - `new-recurring` (merchant "Sample Gym", averageAmount 999) → `"New repeating payment: Sample Gym"` / `"About ₹999 each time"`.
  - `amount-changed` (merchant "Sample Tv", latestAmount 649) → `"Amount changed: Sample Tv"` / `"Now ₹649"`.
  - `running-high` (category "Food", current 9400, priorAverage 6300) → `"Food is ₹3,100 above your usual month"` / `"₹9,400 vs ₹6,300"`. The same strings as `LookAtSection`.
  - `people` rows are dropped; five eligible rows give three.
  - `doneCountsLine(1, 0)` → `"1 skipped"`; `(2, 1)` → `"2 skipped, 1 duplicate"`; `(0, 3)` → `"3 duplicates"`; `(0, 0)` → `null`.
  - `statementMonthOf(null)` → `null`.
  - InsightsScreen: with `useRoute` mocked to return `{ params: { month: '2026-09', nonce: 1 } }`, `aiInsightsApi.lookAt` is called with `'2026-09'`.

- [ ] **Step 2: Run them and watch them fail.** Run: `T src/lib/importDone.test.ts src/screens/InsightsScreen.test.tsx src/components/insights`. Expected: FAIL for the new cases only.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run and pass.** Same command. Expected: PASS.

- [ ] **Step 5: Commit.** `feat(mobile): open Insights on a month, and Done lines from To look at`

---

### Task 7: Done step

**Files:**
- Create: `mobile/src/screens/import/DoneChanges.tsx`: `{ month: string | null; onOpen: (month: string) => void }`.
- Modify: `mobile/src/screens/import/ImportScreen.tsx` (summary step, lines ~1425–1493)
- Test: `DoneChanges.test.tsx`, `ImportScreen.test.tsx`

**Interfaces:**
- Consumes: Task 6 (`statementMonthOf`, `doneLines`, `doneCountsLine`); `lookAtRowsFromServer`, `isAcked`, `ackEntry` (`lib/insightsFeed.ts`).
  - Queries: `['ai-insights', 'look-at', month]` → `aiInsightsApi.lookAt(month)`, and `['insight-acknowledgements']` → `insightsApi.acknowledgements()`. Both use the same keys and functions as `InsightsScreen`, with `retry: false`.

- [ ] **Step 1: Write the failing tests.**
  - `DoneChanges.test.tsx`:
    - with two rows → heading `"September 2026 in Fynora"` (`monthLabelLong`) and both titles;
    - an acknowledged row is hidden;
    - zero rows, a failed request, or `month: null` → renders nothing;
    - pressing a row calls `onOpen('2026-09')`.
  - `ImportScreen.test.tsx`, `describe('done step, idea B')`:
    - title `"Imported 12 transactions"`;
    - `"Into Sample Bank Savings"`;
    - `"1 skipped"`;
    - In and Out amounts shown;
    - warnings shown as today;
    - with a Quick sort count of 3, `"Sort 3 questions"` comes first in `import-summary-actions`, followed by `"See Insights"`, `"Import another"` and `"See them in Transactions"`;
    - with a count of 0, `"See Insights"` is the primary (non-link) button;
    - `"See Insights"` navigates `('Insights', { month: '2026-09', nonce: expect.any(Number) })`;
    - `"See them in Transactions"` navigates with today's `filters` object, unchanged.
  - Existing tests: replace `"View in Ledger"` with `"See them in Transactions"` and `"Import complete"` with the new title. Keep every other assertion.

- [ ] **Step 2: Run them and watch them fail.** Run: `T src/screens/import/DoneChanges.test.tsx src/screens/import/ImportScreen.test.tsx -t "done step, idea B"`. Expected: FAIL.

- [ ] **Step 3: Implement** in the order of the spec's Step 3 list. The `Stat` component and its styles are deleted if nothing else uses them; check with grep.

- [ ] **Step 4: Run and pass.** Run: `T src/screens/import`. Expected: PASS.

- [ ] **Step 5: Commit.** `feat(mobile): Import done step shows what the month now shows`

---

### Task 8: Whole-app verification

- [ ] **Step 1:** Run the whole suite from `mobile/`: `T`. Expected: every suite passes. Report the counts.
- [ ] **Step 2:** Typecheck and lint (commands in Global Constraints). Expected: 0 errors, 0 warnings.
- [ ] **Step 3:** On the iOS simulator against a local backend with synthetic statements, walk all three steps in light and dark mode, and at the largest text size. Screenshot each step. Check by eye:
  - Review Focus 5: the bar doesn't cover the last row or Cancel when scrolled to the end;
  - chips wrap at large text;
  - colours follow the theme.
- [ ] **Step 4:** Fix anything found with a failing test first where it can be tested, re-run Steps 1–2, and commit. List anything not checked.
