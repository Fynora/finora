# Investments Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task, inline in one session (the repository owner does not want subagents). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Investments screen on web and phone around portfolio total and mix, what matures next, money going in, holdings by type, and in-place value updates.

**Architecture:** One pure module, `investmentSummary.ts`, holds every number and status line and is copied byte for byte to both apps. Each app gets small section components under `components/investments/`, and the page or screen owns loading, saving and dialogs. No backend or API change.

**Tech Stack:** React, Tailwind, Vitest and Testing Library (web); Expo SDK 58, React Native, react-native-svg, TanStack Query, Jest and RNTL (phone).

**Spec:** `docs/superpowers/specs/2026-11-18-investments-redesign-design.md`

## Global Constraints

- No backend or API change; no new native code on mobile.
- Every commit message has no AI-attribution trailer. Header at most 100 characters.
- Use Node 22 (`npx -y node@22 -e 'console.log(process.execPath)'`, its folder first on PATH). Run mobile tests with `npm test -- <path>`.
- Never send local test traffic to production: write the worktree's `mobile/.env.local` with only `EXPO_PUBLIC_API_BASE_URL=http://localhost:8081`, and start Metro with `--clear`.
- Amounts: whole rupees, `en-IN` grouping. Facts only, never advice wording. Never show ₹0 for data that failed to load.
- Stored type values stay `Mutual Fund`, `Stocks`, `FD`, `PPF/NPS`, `Other`.
- Existing tokens only. Opaque mobile tiles need a `glass-exempt` marker comment. Test ids must not start with `ring-`.

## Review Focus

1. Holdings that total zero or less: no `NaN`, no `Infinity`, mix bar hidden (Task 1).
2. Month edges: 31 March, January crossing a year, and a maturity date today in a timezone ahead of UTC (Task 1).
3. An older reload landing after a newer save or delete must not bring old values back (Tasks 3 and 5).
4. A bank-linked holding must not offer Update value; an unknown type must land in Other (Tasks 1, 3 and 5).
5. One failed source must not blank the others or print ₹0: accounts, net worth and payments fail independently (Tasks 3 and 5).

---

### Task 1: Shared investment summary module

**Files:**
- Create: `frontend/src/lib/investmentSummary.ts`, `frontend/src/lib/investmentSummary.test.ts`
- Create: `mobile/src/lib/investmentSummary.ts` (byte-identical copy), `mobile/src/lib/investmentSummary.test.ts`

**Interfaces — Produces:**

```ts
export type HoldingGroupKey = 'mutual-funds' | 'deposits' | 'stocks' | 'retirement' | 'other';
export const GROUP_LABEL: Record<HoldingGroupKey, string>; // Mutual funds, Deposits, Stocks, PPF, EPF and NPS, Other
export interface HoldingLike {
  id: string; name: string; balance: number; investmentKind?: string | null;
  principalAmount?: number | null; interestRate?: number | null; maturityDate?: string | null;
  maturityAmount?: number | null; installmentAmount?: number | null;
  installmentsPaid?: number | null; installmentsTotal?: number | null;
}
export interface PaymentLike { date: string; amount: number; reconciliationStatus?: string | null }
type Money = (n: number) => string;

export function groupKeyFor(kind?: string | null): HoldingGroupKey;
export function shareLabel(share: number): string; // "46%", "<1%", "0%"
export function summarizeHoldings<T extends HoldingLike>(holdings: T[]): {
  total: number; count: number;
  groups: { key: HoldingGroupKey; label: string; total: number; share: number; items: { holding: T; share: number }[] }[];
  mix: { key: HoldingGroupKey; label: string; total: number; share: number }[];
};
export function assetShare(total: number, totalAssets?: number | null): number | null;
export function comingUp<T extends HoldingLike>(holdings: T[], today: Date): {
  holding: T; dateLabel: string | null; whenLabel: string | null; amount: number | null;
  paid: number | null; instalments: number | null; progress: number | null;
}[];
export function holdingTerms(holding: HoldingLike, today: Date, money: Money): string;
export function moneyInPeriod(today: Date): { dateFrom: string; dateTo: string };
export function summarizeMoneyIn(payments: PaymentLike[], today: Date): {
  total: number; count: number; monthlyAverage: number;
  months: { key: string; letter: string; total: number; heightFrac: number }[];
};
export function parseHoldingValue(text: string): number | null;
export function holdingValueProblem(text: string): string | null;
export function valueChangeLine(previous: number, next: number | null, money: Money): string | null;
```

- [ ] **Step 1: Write the failing tests** in `frontend/src/lib/investmentSummary.test.ts`, with today pinned to `new Date(2026, 10, 18)`:

```ts
it.each([['Mutual Fund','mutual-funds'],[' fd ','deposits'],['RD','deposits'],['Demat','stocks'],['Stocks','stocks'],
  ['PPF/NPS','retirement'],['EPF','retirement'],['NPS','retirement'],['Other','other'],[null,'other'],['Gold','other']])(
  'groups %j as %s', (kind, key) => expect(groupKeyFor(kind)).toBe(key));
it('orders groups, sorts holdings by value and works out shares', () => {
  const s = summarizeHoldings(SAMPLE); // the six Figma holdings, total 1400000
  expect(s.groups.map((g) => g.key)).toEqual(['mutual-funds', 'deposits', 'stocks', 'retirement']);
  expect(s.groups[0].items[0].holding.name).toBe('Flexi cap fund');
  expect(shareLabel(s.groups[0].share)).toBe('46%');
});
it('stays finite when holdings total zero or less', () => {
  const s = summarizeHoldings([{ id: 'a', name: 'A', balance: 0 }, { id: 'b', name: 'B', balance: -5 }]);
  expect(s.mix).toEqual([]);
  expect(s.groups[0].share).toBe(0);
});
it('reads a tiny share as <1%', () => expect(shareLabel(0.001)).toBe('<1%'));
it('caps and hides the asset share', () => {
  expect(assetShare(1400000, 1840000)).toBeCloseTo(0.76, 2);
  expect(assetShare(200, 100)).toBe(1);
  expect(assetShare(100, 0)).toBeNull();
  expect(assetShare(100, null)).toBeNull();
});
it('lists maturities soonest first, then instalments, at most three', () => { /* rows in that order */ });
it.each([['2026-11-18','today'],['2026-11-19','tomorrow'],['2027-01-12','in 55 days']])(
  'says when %s matures', (maturityDate, whenLabel) => { /* comingUp(...)[0].whenLabel */ });
it('leaves a matured deposit out of Coming up and says so on its row', () => {
  expect(comingUp([{ id: 'a', name: 'FD', balance: 1, maturityDate: '2026-01-03' }], TODAY)).toEqual([]);
  expect(holdingTerms({ id: 'a', name: 'FD', balance: 1, maturityDate: '2026-01-03' }, TODAY, money)).toBe('Matured 3 Jan 2026');
});
it('clamps instalment progress', () => { /* paid 30 of 24 is not listed; paid -1 of 24 gives 0 */ });
it('covers this month and the 11 before it', () => {
  expect(moneyInPeriod(TODAY)).toEqual({ dateFrom: '2025-12-01', dateTo: '2026-11-18' });
  expect(moneyInPeriod(new Date(2026, 2, 31)).dateFrom).toBe('2025-04-01');
  expect(moneyInPeriod(new Date(2027, 0, 5)).dateFrom).toBe('2026-02-01');
});
it('buckets payments by month and ignores replaced copies', () => { /* DUPLICATE and SUPERSEDED excluded; 12 months; tallest heightFrac 1 */ });
it('has no NaN with no payments', () => { /* total 0, average 0, every heightFrac 0 */ });
it.each([['2,60,000',260000],['99.50',99.5]])('reads %j', (t, n) => expect(parseHoldingValue(t)).toBe(n));
it.each(['', '0', '-5', 'abc', '1e5', '12,5', '100.555', '1000000000000'])('refuses %j', (t) => expect(parseHoldingValue(t)).toBeNull());
it('explains decimals and size', () => {
  expect(holdingValueProblem('100.555')).toBe('Current value can have at most 2 decimal places.');
  expect(holdingValueProblem('1000000000000')).toBe('Current value is too large.');
});
it('describes the change', () => {
  expect(valueChangeLine(380000, 395000, money)).toBe('Up ₹15,000 from the last value you saved.');
  expect(valueChangeLine(380000, 370000, money)).toBe('Down ₹10,000 from the last value you saved.');
  expect(valueChangeLine(380000, 380000, money)).toBeNull();
  expect(valueChangeLine(380000, null, money)).toBeNull();
});
```

Fill the two commented bodies and the three shortened cases with real assertions while writing them. `holdingTerms` joins, with " · ": rate as `7.2% a year`, `₹4,000 a month`, `12 of 24 paid`, `matures 12 Jan 2027` or `Matured 3 Jan 2026`, `₹3,21,600 at maturity`; a holding with no terms returns its group's singular name (`Mutual fund`, `Deposit`, `Stocks`, `PPF, EPF or NPS`, `Other`).

- [ ] **Step 2: Run and watch it fail.** `cd frontend && npx vitest run src/lib/investmentSummary.test.ts`. Expected: FAIL, module not found.
- [ ] **Step 3: Implement** with no imports. Parse `YYYY-MM-DD` by splitting into local calendar parts, never `new Date(string)`. Compare days through `Date.UTC(y, m, d)` so daylight saving cannot shift a count.
- [ ] **Step 4: Run and pass.** Same command. Expected: PASS.
- [ ] **Step 5: Copy to mobile** and add `mobile/src/lib/investmentSummary.test.ts` with the same cases plus a parity test reading `../../../frontend/src/lib/investmentSummary.ts` with `fs` (as `mobile/src/lib/budgetPace.test.ts` does). Run `cd mobile && npm test -- src/lib/investmentSummary.test.ts`. Expected: PASS.
- [ ] **Step 6: Commit.** `git commit -m "feat: investment summary shared by web and phone"`

### Task 2: Web section components

**Files:**
- Create in `frontend/src/components/investments/`: `PortfolioCard.tsx`, `ComingUpCard.tsx`, `MoneyGoingInCard.tsx`, `HoldingsCard.tsx`, `NetWorthCard.tsx`, `investmentsSections.test.tsx`

**Interfaces — Consumes:** Task 1. **Produces:**

```ts
PortfolioCard({ summary, money })
ComingUpCard({ rows, money })                     // renders nothing when rows is empty
MoneyGoingInCard({ today })                       // loads its own payments
HoldingsCard({ summary, today, money, onUpdate, onDelete, busyId })
NetWorthCard({ netWorth, failed, money })
```

- [ ] **Step 1: Write the failing tests:** mix bar has `role="img"` with name `Mutual funds 46%, Deposits 25%, Stocks 15%, PPF, EPF and NPS 14%`; `ComingUpCard` with no rows renders nothing; a holding row shows `27%` and its terms; the row menu has Update value and Delete holding; a holding with `primarySource: 'ACCOUNT_AGGREGATOR'` has no Update value; `MoneyGoingInCard` requests `type: 'EXPENSE'` with `dateFrom: '2025-12-01'`, shows `₹1,44,000` and "About ₹12,000 a month", prefixes "At least" when the 10-page cap is hit, says "No SIPs or broker payments found in your statements yet." when empty, and shows its own failure line when the search rejects; "See payments" links to `/app/transactions?category=Investments&from=2025-12-01&to=2026-11-18`; `NetWorthCard` shows the unknown-cards note, the "Building your net worth trend. Check back in a day or two." text with fewer than 2 points, danger ink when negative, and its own failure line when `failed`.
- [ ] **Step 2: Run and watch it fail.** `cd frontend && npx vitest run src/components/investments`. Expected: FAIL.
- [ ] **Step 3: Implement.** Move the paging fetch from `components/InvestmentActivity.tsx` into `MoneyGoingInCard` unchanged (guarded by `useAsyncGuard`). Reuse `components/budgets/RowMenu.tsx`. Keep the chart.js `Line` for the trend. Group colours: `text-accent-blue`-family tokens where they exist; otherwise `COLOR_HEX.blue`, `teal`, `orange`, `purple`, `gray`.
- [ ] **Step 4: Run and pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(frontend): investments portfolio, coming up, money in and holdings sections"`

### Task 3: Web page, dialogs and saving

**Files:**
- Create: `frontend/src/components/investments/HoldingDialogs.tsx`
- Modify: `frontend/src/pages/Investments.tsx`, `frontend/src/pages/Investments.test.tsx`, `frontend/src/api/endpoints.ts` (remove `networthApi.saveSnapshot`)
- Delete: `frontend/src/components/InvestmentActivity.tsx`, `frontend/src/components/InvestmentActivity.test.tsx`

**Interfaces — Produces:** `UpdateValueDialog({ holding, money, onClose, onSave(value) })`, `AddHoldingDialog({ onClose, onSave({ name, balance, investmentKind }) })`.

- [ ] **Step 1: Rewrite `Investments.test.tsx`.** Keep the intent of every existing test that still applies (no false empty state while loading, no skeleton on refetch, superseded refetch cannot resurrect a deleted holding, failed load claims nothing, server message on a refused add, no plan gate, unknown-cards note). Add:
  - Update value sends `accountsApi.update('a1', { name: 'Index Fund', accountType: 'INVESTMENT', balance: 395000 })` and the row shows ₹3,95,000 before the reload returns.
  - Double click on Save value sends one request; Escape while saving does not close the dialog.
  - `100.555` shows "Current value can have at most 2 decimal places." and calls nothing.
  - An older reload arriving after a newer save does not bring the old value back.
  - Accounts failing still shows net worth and says "Could not load your investments." with no ₹ total; net worth failing still lists holdings with no asset share.
  - Delete shows `"Index Fund" will be removed from your net worth. This can't be undone.`
  - No "Save Today's Snapshot" button remains.
  - Saves and deletes invalidate `dashboard-summary`.
- [ ] **Step 2: Run and watch it fail.** `cd frontend && npx vitest run src/pages/Investments.test.tsx`. Expected: FAIL.
- [ ] **Step 3: Implement.** Replace `Promise.all` with `Promise.allSettled`; keep `useAsyncGuard` so only the newest load applies. Wrap the page in a `QueryClient` invalidation through `useQueryClient`, and add `QueryClientProvider` to the test's render helper.
- [ ] **Step 4: Run and pass**, then the full web suite: `npx vitest run`. Expected: all pass.
- [ ] **Step 5: Commit.** `git commit -m "feat(frontend): investments page with update value and add holding dialogs"`

### Task 4: Phone section components

**Files:**
- Create in `mobile/src/components/investments/`: `PortfolioCard.tsx`, `ComingUpCard.tsx`, `MoneyGoingInCard.tsx`, `HoldingsCard.tsx`, `NetWorthCard.tsx`, `investmentsSections.test.tsx`
- Delete: `mobile/src/components/InvestmentActivityCard.tsx`, `mobile/src/components/InvestmentActivityCard.test.tsx`

**Interfaces — Produces:** the same props as Task 2, plus `MoneyGoingInCard({ onSeePayments(categoryId, dateFrom, dateTo) })` and the exported `INVESTMENT_ACTIVITY_QUERY_KEY` moved here.

- [ ] **Step 1: Write the failing tests** with the same assertions as Task 2, adapted to accessibility labels; the three-dot button is labelled `More actions for Flexi cap fund`; at jest's font scale of 2, amounts wrap rather than truncate.
- [ ] **Step 2: Run and watch it fail.** `cd mobile && npm test -- src/components/investments`. Expected: FAIL.
- [ ] **Step 3: Implement** with `Card`, theme tokens, `TrendChart` and `isPausedCold`. Keep the paging query from `InvestmentActivityCard`.
- [ ] **Step 4: Run and pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(mobile): investments portfolio, coming up, money in and holdings sections"`

### Task 5: Phone screen and sheets

**Files:**
- Create: `mobile/src/components/investments/HoldingActionsSheet.tsx`, `UpdateValueSheet.tsx`, `AddHoldingSheet.tsx`
- Modify: `mobile/src/screens/InvestmentsScreen.tsx`, `mobile/src/screens/InvestmentsScreen.test.tsx`, `mobile/src/components/skeletons/ScreenSkeletons.tsx`, `ScreenSkeletons.test.tsx`, `mobile/src/api/endpoints.ts` (remove `networthApi.saveSnapshot`)

**Interfaces — Consumes:** `components/budgets/BudgetSheet.tsx` as the sheet shell.

- [ ] **Step 1: Rewrite the screen tests.** Keep every applicable existing test (one haptic per outcome, no false ₹0 offline, holdings survive a net worth failure, negative net worth in danger ink, screen-capture protection, skeleton on first load). Add the Task 3 cases for Update value, plus: a failed refresh keeps the holdings on screen; the saved value shows before the refetch returns; a deleted holding leaves the list even if the refetch fails; See payments navigates to Transactions with `categoryId`, `dateFrom`, `dateTo`, a label and a nonce.
- [ ] **Step 2: Run and watch it fail.** `cd mobile && npm test -- src/screens/InvestmentsScreen.test.tsx`. Expected: FAIL.
- [ ] **Step 3: Implement.** Gate failure on `isError && data === undefined`. Patch `['accounts']` with `setQueryData` after a save or delete, then invalidate `accounts`, `networth` and `dashboard-summary`. Update the skeleton to the new order.
- [ ] **Step 4: Run and pass**, then `npm test`, `npm run lint` and `npx tsc --noEmit`. Expected: all pass.
- [ ] **Step 5: Commit.** `git commit -m "feat(mobile): investments screen with update value and add holding sheets"`

### Task 6: Check it for real, then open the PR

**Files:** only what these checks show needs fixing, each fix test-first.

- [ ] **Step 1: Full checks on Node 22.** Web: eslint, `npx tsc --noEmit -p .`, `npx vitest run`, `npm run build`. Mobile: `npm run lint`, `npx tsc --noEmit`, `npm test`. Expected: all pass.
- [ ] **Step 2: Web in Chrome** on the local e2e stack (`e2e/scripts/stack.mjs up`; Vite on 5174 with `FINORA_API_PROXY_TARGET=http://localhost:8081`), with seeded holdings of every type, an FD, an RD and Investments payments. Check values against the seed, update, add, delete, See payments, light and dark, and 1440, 1100 and 375 px.
- [ ] **Step 3: Phone on an iOS simulator** against the same stack. Confirm the first request reaches the local backend before typing a password. Check the same flows, dark mode and the largest text size with the keyboard open.
- [ ] **Step 4: Android.** Run the same flows on an Android emulator (`-gpu host`), or state plainly in the PR that it was not run.
- [ ] **Step 5: Push and open the PR** against `main` with what changed, how it was checked and what was not covered.
