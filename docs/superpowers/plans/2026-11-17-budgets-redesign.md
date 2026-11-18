# Budgets Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Budgets screen on web and mobile around pace: a ring hero, safe-to-spend per
day, a month-end forecast, risk groups, edit-in-place, and a three-dot menu per budget.

**Architecture:** One pure module per app (`budgetPace.ts`, byte-identical in both, guarded by a
parity test) computes every number and every status string from the five `Budget` fields and
today's date. The two screens only lay those out. No backend change.

**Tech Stack:** Web: React 18, Vite, Tailwind, Vitest + Testing Library. Mobile: Expo SDK (see
`mobile/package.json`), React Native, TanStack Query, `react-native-svg` 15.16.1, Jest + RNTL.

**Spec:** `docs/superpowers/specs/2026-11-17-budgets-redesign-design.md`
**Figma:** https://www.figma.com/design/NdqLc0fe006GT3maC7qJUo

## Global Constraints

- No backend, API or migration change. Only `GET/PUT/DELETE /api/v1/budgets` as today.
- No new npm dependency in `frontend/` or `mobile/`. Mobile must stay OTA-shippable (JS only).
- Run Node tooling as CI does: `npx -y node@22 …` (local Node differs from CI's Node 22).
- Fresh worktree: run `npm ci` at the repo root (commit hook), in `frontend/` and in `mobile/`.
- Commit messages carry no `Co-Authored-By` or other AI trailer (repo `CLAUDE.md`).
- Amounts: whole rupees, `en-IN` grouping, via each app's existing formatter (web `fmt` in
  `Budgets.tsx` — move it to the pace module's callers unchanged; mobile `fmtCurrency`).
- Status colours: bars use fill tokens (success / warning / danger); text uses ink tokens.
- Fonts: Manrope for figures and headings, Inter for the rest (already loaded in both apps).
- Keep every existing behaviour the spec doesn't change: cache invalidations, haptics,
  screen-capture prevention, single-flight, pull-to-refresh, skeletons, confirm-before-remove text.
- Mac has 16 GB: at most two heavy processes (test runs, builds, simulator) at once.

## Review Focus

1. **Last day of the month** (`daysLeft = 0`): no division by zero, no "₹Infinity / day"; the card
   reads "Last day · ₹X left". Tests in Task 1 (module) and Tasks 3 and 5 (render).
2. **One budget, or all budgets in one group**: empty groups are not rendered, counts are right.
   Tests in Tasks 3 and 5.
3. **Long category name at large text size** (jest runs at fontScale 2): the three-dot button
   stays visible with its label; the name wraps, it doesn't push the button off. Test in Task 5.
4. **Categories request fails**: budgets still render, with the fallback icon. Existing web test
   kept (Task 3); new mobile test (Task 5).
5. **Edit limit with bad input or a double tap** (empty, `0`, `-5`, `abc`; two quick Saves): an
   inline message, no `upsert` call for bad input, exactly one call for a double tap. Tests in
   Tasks 4 and 6.

---

### Task 1: Pace module (web + mobile copy + parity guard)

**Files:**
- Create: `frontend/src/lib/budgetPace.ts`, `frontend/src/lib/budgetPace.test.ts`
- Create: `mobile/src/lib/budgetPace.ts` (byte-identical copy), `mobile/src/lib/budgetPace.test.ts`

**Interfaces:**
- Produces (exact names, used by every later task):

```ts
export type BudgetGroup = 'over' | 'heading-over' | 'on-track';
export interface MonthClock {
  dayOfMonth: number; daysInMonth: number; daysLeft: number;
  monthFrac: number;            // dayOfMonth / daysInMonth
  monthEndLabel: string;        // "30 Nov" (en-IN, day numeric + month short)
  monthLabel: string;           // "November 2026"
}
export interface BudgetPace {
  spentFrac: number; remaining: number; safePerDay: number; forecast: number;
  overBy: number;               // max(0, spent - limit)
  forecastOverBy: number;       // max(0, forecast - limit)
  group: BudgetGroup;
}
export interface PaceCopy { main: string; sub: string }
type Money = (n: number) => string;

export function monthClock(today: Date): MonthClock;
export function budgetPace(spent: number, limit: number, clock: MonthClock): BudgetPace;
export function overallPace(budgets: { spentThisMonth: number; monthlyLimit: number }[], clock: MonthClock): BudgetPace;
export function groupBudgets<T extends { spentThisMonth: number; monthlyLimit: number }>(
  budgets: T[], clock: MonthClock,
): { group: BudgetGroup; items: { budget: T; pace: BudgetPace }[] }[];
export function describePace(p: BudgetPace, clock: MonthClock, money: Money): PaceCopy;
export function paceA11yLabel(name: string, spent: number, limit: number, p: BudgetPace, clock: MonthClock, money: Money): string;
export const GROUP_LABEL: Record<BudgetGroup, string>;
```

Formulas are the spec's "The numbers" section verbatim. Sorting (in `groupBudgets`): over by
`overBy` desc; heading-over by `forecastOverBy` desc; on-track by `spentFrac` desc. Group order
over, heading-over, on-track; groups with no items are omitted.

Exact copy (the module owns it so both apps match):
- `GROUP_LABEL`: `over` "Over", `heading-over` "Heading over at this pace", `on-track` "On track".
- `describePace`:
  - over: main `"{overBy} over"`, sub `"{money(0)} left to spend"`
  - daysLeft = 0 and not over: main `"Last day · {remaining} left"`, sub `""`
  - heading-over: main `"{safePerDay} / day safe"`, sub `"Heading {forecastOverBy} over by {monthEndLabel}"`
  - on-track: main `"{safePerDay} / day safe"`, sub `"About {forecast} by {monthEndLabel}"`
- `paceA11yLabel`: `"{name}: {spent} spent of {limit}. "` then over: `"{overBy} over budget."`;
  last day: `"Last day, {remaining} left."`; otherwise `"{safePerDay} a day safe for {daysLeft}
  days."`, plus for heading-over `" Heading {forecastOverBy} over by {monthEndLabel}."`

- [ ] **Step 1: Write the failing tests** in `frontend/src/lib/budgetPace.test.ts`, using
  `const clock = monthClock(new Date(2026, 10, 17))` and `money = n => '₹' + n.toLocaleString('en-IN')`:

```ts
it('reads 17 Nov 2026 as day 17 of 30 with 13 left', () =>
  expect(clock).toMatchObject({ dayOfMonth: 17, daysInMonth: 30, daysLeft: 13, monthEndLabel: '30 Nov', monthLabel: 'November 2026' }));
it('handles a leap February', () =>
  expect(monthClock(new Date(2028, 1, 29))).toMatchObject({ daysInMonth: 29, daysLeft: 0 }));
it('food 7,200 of 9,000 on day 17 is heading over', () =>
  expect(budgetPace(7200, 9000, clock)).toMatchObject({ safePerDay: 138, forecast: 12706, forecastOverBy: 3706, group: 'heading-over' }));
it('shopping 8,400 of 6,000 is over with nothing safe', () =>
  expect(budgetPace(8400, 6000, clock)).toMatchObject({ overBy: 2400, safePerDay: 0, group: 'over' }));
it('groceries 4,500 of 8,000 is on track', () =>
  expect(budgetPace(4500, 8000, clock)).toMatchObject({ safePerDay: 269, forecast: 7941, group: 'on-track' }));
it('spent exactly the limit counts as over', () => expect(budgetPace(9000, 9000, clock).group).toBe('over'));
it('forecast exactly on the limit stays on track', () =>
  expect(budgetPace(100, 3000, monthClock(new Date(2026, 10, 1))).group).toBe('on-track'));
it('nothing spent: forecast 0, on track, full limit spread over days left', () =>
  expect(budgetPace(0, 1300, clock)).toMatchObject({ forecast: 0, safePerDay: 100, group: 'on-track' }));
it('last day: safePerDay 0, never Infinity', () =>
  expect(budgetPace(1000, 2800, monthClock(new Date(2026, 10, 30))).safePerDay).toBe(0));
it('overall sums: 22,700 of 29,000', () =>
  expect(overallPace(SAMPLE, clock)).toMatchObject({ safePerDay: 484, forecast: 40059, forecastOverBy: 11059 }));
it('groups and sorts, omitting empty groups', () => {
  const g = groupBudgets(SAMPLE, clock);
  expect(g.map(x => x.group)).toEqual(['over', 'heading-over', 'on-track']);
  expect(g[2].items.map(i => i.budget.categoryName)).toEqual(['Groceries', 'Fuel', 'Entertainment']);
  expect(groupBudgets([SAMPLE[2]], clock).map(x => x.group)).toEqual(['on-track']);
});
it('describes each state in the agreed words', () => {
  expect(describePace(budgetPace(8400, 6000, clock), clock, money)).toEqual({ main: '₹2,400 over', sub: '₹0 left to spend' });
  expect(describePace(budgetPace(7200, 9000, clock), clock, money)).toEqual({ main: '₹138 / day safe', sub: 'Heading ₹3,706 over by 30 Nov' });
  expect(describePace(budgetPace(4500, 8000, clock), clock, money)).toEqual({ main: '₹269 / day safe', sub: 'About ₹7,941 by 30 Nov' });
  const last = monthClock(new Date(2026, 10, 30));
  expect(describePace(budgetPace(1000, 2800, last), last, money)).toEqual({ main: 'Last day · ₹1,800 left', sub: '' });
});
it('builds one spoken label per budget', () =>
  expect(paceA11yLabel('Food and dining', 7200, 9000, budgetPace(7200, 9000, clock), clock, money))
    .toBe('Food and dining: ₹7,200 spent of ₹9,000. ₹138 a day safe for 13 days. Heading ₹3,706 over by 30 Nov.'));
```

`SAMPLE` = Shopping 8400/6000, Food and dining 7200/9000, Groceries 4500/8000, Fuel 2000/4000,
Entertainment 600/2000 (shape `{ categoryName, spentThisMonth, monthlyLimit }`).

- [ ] **Step 2: Run, expect FAIL** (module missing):
  `cd frontend && npx -y node@22 ./node_modules/.bin/vitest run src/lib/budgetPace.test.ts`
- [ ] **Step 3: Implement** `frontend/src/lib/budgetPace.ts` (pure; no React, no imports from the
  app, so it can be copied verbatim). Use `Intl.DateTimeFormat('en-IN', …)` for both labels.
- [ ] **Step 4: Run, expect PASS** (same command).
- [ ] **Step 5: Copy to mobile.** `cp frontend/src/lib/budgetPace.ts mobile/src/lib/budgetPace.ts`;
  port the same tests to Jest in `mobile/src/lib/budgetPace.test.ts`, plus:

```ts
it('is byte-identical to the web module', () => {
  const web = fs.readFileSync(path.join(__dirname, '../../../frontend/src/lib/budgetPace.ts'), 'utf8');
  expect(fs.readFileSync(path.join(__dirname, 'budgetPace.ts'), 'utf8')).toBe(web);
});
```

- [ ] **Step 6: Run mobile, expect PASS:**
  `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx -y node@22 ./node_modules/.bin/jest src/lib/budgetPace.test.ts`
  (If Hermes/ICU in jest formats the month differently, fix in the module, not the test.)
- [ ] **Step 7: Commit** `feat: budget pace module -- safe per day, forecast and risk groups, same in web and mobile`

---

### Task 2: Web ring and hero

**Files:**
- Create: `frontend/src/components/budgets/BudgetRing.tsx`, `frontend/src/components/budgets/BudgetHero.tsx`,
  `frontend/src/components/budgets/BudgetHero.test.tsx`

**Interfaces:**
- Consumes: `monthClock`, `overallPace`, `BudgetPace`, `MonthClock` (Task 1).
- Produces: `BudgetRing({ spentFrac, monthFrac, tone, size, children })` (inline SVG, two arcs:
  outer = `min(1, spentFrac)`, thin inner = `monthFrac`; `aria-hidden`);
  `BudgetHero({ totalSpent, totalLimit, overall, clock, money })`.

Hero copy (spec, web section 2): centre `"{round(spentFrac*100)}%"` + "spent"; three figures —
"Spent of {totalLimit}" / {totalSpent} / "{round(monthFrac*100)}% of the month gone, {round(spentFrac*100)}% of budget used";
"Safe to spend" / {safePerDay} "/ day" / "{daysLeft} days left · across all budgets" (last day:
"Last day · {remaining} left"); "At this pace by {monthEndLabel}" / {forecast} / over: danger
"{forecastOverBy} over your budgets", else muted "{totalLimit - forecast} under your budgets".
Ring tone by `overall.group` (danger / warning / success). The hero is one `role="group"` with
`aria-label` summarising the three figures.

- [ ] **Step 1: Failing tests** in `BudgetHero.test.tsx`: renders "₹22,700", "₹484", "₹40,059",
  "₹11,059 over your budgets" for SAMPLE on 17 Nov 2026; renders "₹17,647" and "₹12,353 under
  your budgets" when totals are 10,000 of 30,000 on day 17; last-day variant shows "Last day ·".
- [ ] **Step 2: Run, expect FAIL.** `cd frontend && npx -y node@22 ./node_modules/.bin/vitest run src/components/budgets`
- [ ] **Step 3: Implement** both components with design-system tokens (`FinoraCard` for the shell).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(web): budgets hero -- ring, safe per day and month-end forecast`

---

### Task 3: Web grouped list, row menu, page wiring

**Files:**
- Create: `frontend/src/components/budgets/BudgetRow.tsx`, `frontend/src/components/budgets/RowMenu.tsx`,
  `frontend/src/components/budgets/PaceBar.tsx`
- Modify: `frontend/src/pages/Budgets.tsx` (replace `MetricCard` grid, form card and doughnut)
- Test: `frontend/src/pages/Budgets.test.tsx` (rewrite cases listed below)

**Interfaces:**
- Consumes: Task 1 exports; `BudgetHero` (Task 2); existing `budgetsApi`, `categoriesApi`,
  `ConfirmDialog`, `EmptyState`, `Skeleton`, `ICON_COMPONENTS`, `COLOR_HEX`.
- Produces: `PaceBar({ spentFrac, monthFrac, tone })` (marker at `monthFrac`, `role="img"`
  label "Spent 80%, month 57% gone"); `RowMenu({ label, items: { label, onSelect, danger? }[] })`
  — button `aria-haspopup="menu"`, `role="menu"`/`menuitem`, Esc and outside click close, focus
  returns to the button; `BudgetRow({ budget, pace, clock, category, money, onEdit, onRemove, removing })`.
  Task 4 adds the edit state to `BudgetRow`.

Page layout per spec web section 1–4; legend text: "Marks where spending should be today if you
spread each budget evenly over the month. Dotted limits can be edited in place."
Menu items: "Edit limit", "See transactions" (link to
`/app/transactions?category={encodeURIComponent(name)}&from={YYYY-MM-01}&to={YYYY-MM-last}`, the
params `frontend/src/lib/ledgerUrlFilters.ts` reads), "Remove budget" (opens today's
`ConfirmDialog`, text unchanged). No budgets: hide hero and list, show `EmptyState` titled
"No budgets set" with desc "Choose New budget to start tracking a category." (builder's call, not
in spec: a ring over zero budgets says nothing).

- [ ] **Step 1: Rewrite tests.** Delete the stat-card, 90%-pill and Spending Breakdown cases and the
  `react-chartjs-2` mock. Keep: categories fetched alongside, icon colour, categories-fail
  fallback, loading never shows empty, empty after load, error state, remove confirm / cancel /
  server message. Add (fake the date with `vi.setSystemTime(new Date(2026, 10, 17))`):
  - `'groups budgets as Over, Heading over at this pace and On track, problems first'`
    (headings with counts "Over · 1", "Heading over at this pace · 1", "On track · 3"; row order).
  - `'hides groups that have no budgets'` (one on-track budget → only "On track · 1").
  - `'shows per-day safe amounts and the forecast line on each row'` ("₹138 / day safe", "Heading ₹3,706 over by 30 Nov").
  - `'reads Last day on the final day of the month'` (`setSystemTime(2026-11-30)` → "Last day · ₹1,800 left").
  - `'See transactions links to the ledger filtered to the category and this month'`
    (href `/app/transactions?category=Food%20and%20dining&from=2026-11-01&to=2026-11-30`).
  - `'the row menu closes on Escape and returns focus to its button'`.
- [ ] **Step 2: Run, expect FAIL.** `cd frontend && npx -y node@22 ./node_modules/.bin/vitest run src/pages/Budgets.test.tsx`
- [ ] **Step 3: Implement** `PaceBar`, `RowMenu`, `BudgetRow`; rewire `Budgets.tsx` (keep `load()`,
  `allSettled`, the three invalidations, `useDelayedLoading`; skeleton = ring circle + 3 rows).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(web): budgets list grouped by risk with pace bars and a row menu`

---

### Task 4: Web edit-in-place and New budget dialog

**Files:**
- Create: `frontend/src/components/budgets/LimitEditor.tsx`, `frontend/src/components/budgets/NewBudgetDialog.tsx`
- Modify: `frontend/src/components/budgets/BudgetRow.tsx`, `frontend/src/pages/Budgets.tsx`
- Test: `frontend/src/pages/Budgets.test.tsx`

**Interfaces:**
- Consumes: `BudgetRow` (Task 3), `CategoryCombobox({ value, onChange, inputId })`, `useDialogA11y`,
  `budgetsApi.upsert(categoryName, monthlyLimit)`.
- Produces: `LimitEditor({ initial, onSave(limit: number): Promise<void>, onCancel })` — number
  input + "+500" / "+1,000" chips (add to the current value) + Cancel / Save; Enter saves, Esc
  cancels; disabled-while-saving guard. `NewBudgetDialog({ open, onClose, onSaved })`.

Validation message (same as today): "Monthly limit must be greater than zero." Category required:
"Pick a category first." Save error: server message or "Could not save this budget. Try again."
After any save: today's three invalidations.

- [ ] **Step 1: Failing tests:**
  - `'clicking the dotted limit edits it in place and saves the new amount'` (upsert called with ("Food and dining", 9500) after "+500").
  - `'Escape cancels an in-place edit without saving'`.
  - `'refuses an empty, zero, negative or non-number limit'` (each: message shown, upsert not called).
  - `'a double click on Save sends one request'`.
  - `'New budget saves the category picked in the dialog'` (replaces "still saves a budget through the restyled form").
  - `'New budget refuses to save without a category'`.
- [ ] **Step 2: Run, expect FAIL** (command as Task 3).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.** Then the whole web suite, lint and build:
  `cd frontend && npx -y node@22 ./node_modules/.bin/vitest run && npx -y node@22 npm run lint && npx -y node@22 npm run build`
- [ ] **Step 5: Commit** `feat(web): edit a budget limit in place, and pick the category for a new budget`

---

### Task 5: Mobile hero and grouped cards

**Files:**
- Create: `mobile/src/components/budgets/BudgetRing.tsx`, `mobile/src/components/budgets/BudgetHeroCard.tsx`,
  `mobile/src/components/budgets/BudgetCard.tsx`, `mobile/src/components/budgets/PaceBar.tsx`
- Modify: `mobile/src/screens/BudgetsScreen.tsx` (drop the form card; header "+" via
  `navigation.setOptions({ headerRight })`)
- Test: `mobile/src/screens/BudgetsScreen.test.tsx`, `mobile/src/components/budgets/BudgetCard.test.tsx`

**Interfaces:**
- Consumes: Task 1 exports; `fmtCurrency`, `currentYearMonth`, `monthDateRange`, `monthLabel`
  (`mobile/src/lib/format.ts`); `iconNameFor`, `colorHexFor` (`mobile/src/lib/categoryIcons.ts`);
  categories list via the same API `CategoryPickerModal` uses; `react-native-svg`.
- Produces: `BudgetCard({ budget, pace, clock, iconName, color, onOpen, onMore })` — the card body
  is one accessible Pressable (label from `paceA11yLabel`, hint "Opens these transactions"); the
  three-dot button is a sibling Pressable (44pt, label "More actions for {name}").
  `onMore` is wired in Task 6.

Hero per spec mobile section 2: centre "{totalSpent}" / "of {totalLimit}"; tiles "{safePerDay}
/ day · Safe to spend" and "{daysLeft} days · Left · day {d} of {n}"; forecast line "At this pace
you reach {forecast} by {monthEndLabel}, {forecastOverBy} over your budgets." (danger tint) or
"…, {under} under your budgets." (neutral). The ring is one accessible element whose label reads
the three hero figures ("₹22,700 spent of ₹29,000. ₹484 a day safe for 13 days. At this pace
₹40,059 by 30 Nov."). Glass surfaces with the existing Reduce Transparency
fallback. Hint under the list: "Tap a card to see its transactions. The three-dot button edits or
removes." Empty state: "No budgets set yet. Tap + to set one." with action "Set your first budget".
Phone cards use `describePace` strings unchanged (Figma shows "₹138 / day"; the shared copy
"₹138 / day safe" wins, so both apps read the same).

- [ ] **Step 1: Rewrite / add tests** (fix the clock with `jest.useFakeTimers({ now: new Date(2026, 10, 17) })`
  or the repo's existing date-mock pattern; Reanimated is mocked per the repo's existing setup):
  - replace `'states what is left, in words…'` with `'shows per-day safe amounts and the forecast line on each card'`.
  - keep `'says how far over budget an overspent category is'` (now "₹2,400 over").
  - `'groups cards as Over, Heading over at this pace and On track'` and `'hides empty groups'`.
  - `'reads Last day on the final day of the month'`.
  - `'keeps the three-dot button visible for a long category name at large text'` (name
    "Entertainment and subscriptions for the whole family"; button found by its label).
  - `'still shows budgets with a neutral icon when categories fail to load'`.
  - keep the drill-through, skeleton (update: "+" usable while loading, no form), screen-capture,
    and "one Loading region" tests.
  - update `'says so plainly when no budgets are set'` to the new empty text.
- [ ] **Step 2: Run, expect FAIL.**
  `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx -y node@22 ./node_modules/.bin/jest src/screens/BudgetsScreen.test.tsx src/components/budgets`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(mobile): budgets hero ring and cards grouped by risk with pace bars`

---

### Task 6: Mobile three-dot menu, Edit limit sheet, New budget sheet

**Files:**
- Create: `mobile/src/components/budgets/BudgetActionsSheet.tsx`, `mobile/src/components/budgets/EditLimitSheet.tsx`,
  `mobile/src/components/budgets/NewBudgetSheet.tsx`
- Modify: `mobile/src/screens/BudgetsScreen.tsx`
- Test: `mobile/src/screens/BudgetsScreen.test.tsx`

**Interfaces:**
- Consumes: `AppModal` (same pattern as `components/dashboard/QuickActionSheet.tsx`),
  `CategoryPickerModal`, `TextField`, `Button`, `parsePositiveAmount`, `useSingleFlight`,
  haptics, `AppAlert`, `budgetsApi`.
- Produces: `BudgetActionsSheet({ budget, visible, onClose, onEdit, onSeeTransactions, onRemove })`
  — items "Edit limit", "See transactions", "Remove budget" (danger ink).
  `EditLimitSheet({ budget, clock, visible, onClose, onSaved })` — field prefilled, chips "+500",
  "+1,000", "+2,000", hint "{spent} spent so far. At {limit} you can spend {perDay} a day for the
  next {daysLeft} days." (recomputed live from the typed amount with `budgetPace`), button "Save limit".
  `NewBudgetSheet({ visible, onClose, onSaved })` — "Choose a category" row opening
  `CategoryPickerModal`, limit field, "Save budget".

Modal rule: an action that opens another modal first closes the actions sheet and opens the next
one only after the sheet's `onDismiss` (iOS) / next frame (Android). Never two `AppModal`s visible
at once — stacked native modals have frozen touches on iOS. Saves keep today's messages, haptics
and invalidations (`budgets`, `dashboard-summary`, `timeline`). Remove keeps today's `AppAlert`
text and calls.

- [ ] **Step 1: Tests:**
  - `'the three-dot button opens Edit limit, See transactions and Remove budget'`.
  - `'Edit limit saves the new amount and refreshes the list'` (upsert("Food and dining", 9500) after "+500").
  - `'Edit limit refuses an empty, zero, negative or non-number amount'` (warning haptic, no upsert).
  - `'a double tap on Save limit sends one request'`.
  - `'only one sheet is open at a time when moving from the menu to Edit limit'` (menu sheet not
    rendered / not visible once the edit sheet is).
  - move the existing save / picker / create-category / refuse / error-haptic / Journey tests to the
    New budget sheet opened from "+".
  - update remove tests to go through the menu; keep `'pressing Remove does not also open the transactions'`.
- [ ] **Step 2: Run, expect FAIL** (command as Task 5).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.** Then the whole mobile suite, lint and typecheck:
  `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx -y node@22 ./node_modules/.bin/jest && npx -y node@22 npm run lint && npx -y node@22 npm run typecheck`
- [ ] **Step 5: Commit** `feat(mobile): budget actions menu, edit-limit sheet and new-budget sheet`

---

### Task 7: Verify in the running apps

No new code unless something here fails (then fix, re-run the affected suite, commit `fix: …`).

- [ ] **Web in the browser** (start Vite from this worktree's `frontend/` on 5173; `preview_start`
  by name serves the primary checkout): signed-in Budgets page at ~1440 px and ~800 px, light and
  dark; edit in place, menu, See transactions lands on the filtered Ledger, remove, New budget.
  Screenshot each.
- [ ] **Contrast e2e** still passes for `/app/budgets` (`e2e/tests/user-portal/contrast.spec.ts`).
- [ ] **Mobile in the simulator**: list, menu → Edit limit (touches still work after the sheet
  closes — the stacked-modal check), New budget, remove, drill-through; light, dark, Reduce
  Transparency on, largest text size. Screenshot each.
- [ ] **Diff read-through** against the spec's sections; every Review Focus item has its test.
- [ ] **Commit** any fixes; push and open the PR per repo rules (check `gh repo view --json visibility`
  first: public → open the PR; private → ask Sid before opening one).
