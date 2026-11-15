# Quick sort Answer Payoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After each Quick sort answer, show one line saying what the answer filed and, when true, that Fynora will remember it for the payee.

**Architecture:** `QuickSortService.answer` already knows the rows it filed and whether the payee key names one payee. It returns those facts in `AnswerResult`. Each app turns the result into one line through a small pure helper, and shows it in a polite live region in both Quick sort variants.

**Tech Stack:** Spring Boot + JPA (backend ITs on `AbstractIntegrationTest`), React + Vitest (web), Expo React Native + Jest/RNTL (mobile), openapi-typescript 7.13.0.

**Spec:** `docs/superpowers/specs/2026-11-15-quick-sort-answer-payoff-design.md`

## Global Constraints

- Remembered line, exactly: `✓ <category> · <n> payment(s) filed (<money>). Fynora will remember this for <payee>.`, with "payment" when n is 1 and "payments" otherwise.
- Line when not remembered, or when the server omits `amount` or `remembered`: `✓ Filed as <category>.`
- Money uses each app's existing rounded rupee format (web `fmt` in QuickSortCard, mobile `fmtCurrency`).
- `remembered` is true exactly when `CounterpartyIdentity.identifiesOnePayee(anchor.getCounterpartyKey())`.
- `amount` is the sum of `abs(amount)` over exactly the rows the answer filed (the anchor plus `otherWaiting`).
- The line stays until the next answer or skip, in both the full and the Dashboard variant, and also shows above "All sorted" and above the Dashboard ending.
- Screen readers announce it once: on web, a `role="status"` element that is always rendered (empty when there is no line); on mobile, `AccessibilityInfo.announceForAccessibility(line)` when a new line is set.
- Node 22 for all JS commands (`npx --yes node@22 ...`). Commits carry no AI trailer. Commit scopes are lower-case.

## Review Focus

1. A failed answer (API error) must not show a new payoff line, and must not clear the error message.
2. An older backend returning only `{ filed }` must give "✓ Filed as <category>.", never "undefined" or "₹NaN".
3. With n = 1 the line must say "1 payment", not "1 payments".
4. A row whose key names no one payee (a `cut:` key) gives `remembered: false` and an amount for that row only.
5. A waiting row from the same payee that is not filed (category set by hand) must not be counted in `amount`.

---

### Task 1: Backend reports amount and remembered

**Files:**
- Modify: `backend/src/main/java/com/finora/transactions/QuickSortDto.java` (`AnswerResult`, and its javadoc)
- Modify: `backend/src/main/java/com/finora/transactions/QuickSortService.java` (`answer`)
- Test: `backend/src/test/java/com/finora/transactions/QuickSortIT.java`
- Regenerate: `backend/openapi/openapi.json`, `frontend/src/api/generated-types.ts`, `mobile/src/api/generated-types.ts`, `admin-portal/src/api/generated-types.ts`

**Interfaces:**
- Produces: `public record AnswerResult(int filed, java.math.BigDecimal amount, boolean remembered)`, as JSON `{ filed, amount, remembered }`.

- [ ] **Step 1: Extend the existing ITs with failing assertions**
  - `answeringFilesEveryWaitingPaymentOfThatPayeeAndRemembersIt`: add `assertThat(answer.get("amount").decimalValue()).isEqualByComparingTo("300"); assertThat(answer.get("remembered").asBoolean()).isTrue();`
  - `answeringARowOnAKeyThatNamesNoOneFilesOnlyThatRow`: add `amount` = `100` and `remembered` false.
  - `answeringNeverRefilesAPaymentOfThePayeeThatWasNotWaiting`: add `amount` equal to the sum of only the rows it asserts were filed.

- [ ] **Step 2: Run them and see them fail**

Run: `cd backend && ./mvnw -q verify -Dit.test=QuickSortIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `amount`/`remembered` missing (null node).

- [ ] **Step 3: Implement.** In `answer`, collect the waiting `Transaction`s (not only their ids), sum `abs(amount)` over them plus the anchor, and return `new AnswerResult(filed.size(), amount, onePayee)`. Update `AnswerResult`'s javadoc for the two new fields.

- [ ] **Step 4: Run QuickSortIT and QuickSortServiceTest**

Run: the Step 2 command, then `./mvnw -q test -Dtest=QuickSortServiceTest`
Expected: all pass.

- [ ] **Step 5: Regenerate OpenAPI and the three clients' types.** Start a throwaway Postgres on 55434 (finora/finora/finora), run `DB_PORT=55434 backend/scripts/generate-openapi-spec.sh`, then `npm run generate:types` in `frontend`, `mobile` and `admin-portal`. Expected diff: only `AnswerResult` gains `amount` and `remembered`.

- [ ] **Step 6: Commit** `feat(backend): quick sort answer reports the money filed and whether the payee is remembered`

### Task 2: Web payoff line

**Files:**
- Create: `frontend/src/lib/quickSortPayoff.ts`, `frontend/src/lib/quickSortPayoff.test.ts`
- Modify: `frontend/src/api/endpoints.ts` (`quickSortAnswer` return type)
- Modify: `frontend/src/components/QuickSortCard.tsx`
- Test: `frontend/src/components/QuickSortCard.test.tsx`

**Interfaces:**
- Consumes: `{ filed, amount, remembered }` from Task 1.
- Produces: `export type QuickSortAnswerResult = { filed: number; amount?: number; remembered?: boolean }` and `export function payoffLine(result: QuickSortAnswerResult, category: string, payee: string, money: (n: number) => string): string` in `quickSortPayoff.ts`.

- [ ] **Step 1: Write the failing helper tests** (`quickSortPayoff.test.ts`, `money = (n) => '₹' + n`):
  - `{filed:3, amount:2400, remembered:true}`, Food, Swiggy → `'✓ Food · 3 payments filed (₹2400). Fynora will remember this for Swiggy.'`
  - `{filed:1, amount:450, remembered:true}` → `'✓ Food · 1 payment filed (₹450). Fynora will remember this for Swiggy.'`
  - `{filed:1, amount:450, remembered:false}` → `'✓ Filed as Food.'`
  - `{filed:3}` (older server) → `'✓ Filed as Food.'`

- [ ] **Step 2: Run and see them fail.** Run: `cd frontend && npx --yes node@22 node_modules/.bin/vitest run src/lib/quickSortPayoff.test.ts`. Expected: FAIL, module not found.

- [ ] **Step 3: Implement `payoffLine`, and type `quickSortAnswer` as returning `QuickSortAnswerResult`.**

- [ ] **Step 4: Write the failing card tests** in `QuickSortCard.test.tsx`, mocking `quickSortAnswer` to resolve `{ filed: 3, amount: 2400, remembered: true }`:
  - full variant: after answering, `screen.getByRole('status')` has the text `✓ Groceries · 3 payments filed (₹2,400). Fynora will remember this for <payee of txn-1>.`
  - the line is replaced on the next answer, and empty after a skip;
  - after the last answer, the line shows together with "All sorted.";
  - dashboard variant: the same line after an answer;
  - answer rejects: the status stays empty and "Couldn't save that answer — please try again." shows;
  - mock resolves `{ filed: 1 }`: status reads `✓ Filed as Groceries.`

- [ ] **Step 5: Run and see them fail.** Run: `npx --yes node@22 node_modules/.bin/vitest run src/components/QuickSortCard.test.tsx`

- [ ] **Step 6: Implement in QuickSortCard.** Add `const [payoff, setPayoff] = useState<string | null>(null)`. In `answer`, set it from the API result with `payoffLine(result, category, q.payee, fmt)`. Clear it in `skip`. Render `<p role="status" className="text-sm text-ink">{payoff ?? ''}</p>` right under the header in both variants' returned sections, so it is present before any answer.

- [ ] **Step 7: Run the web suite, tsc and eslint.**

Run: `npx --yes node@22 node_modules/.bin/vitest run`, then `npx --yes node@22 node_modules/.bin/tsc --noEmit -p tsconfig.json`, then `npx --yes node@22 node_modules/.bin/eslint src/lib/quickSortPayoff.ts src/components/QuickSortCard.tsx src/components/QuickSortCard.test.tsx src/api/endpoints.ts`
Expected: all pass.

- [ ] **Step 8: Commit** `feat(web): quick sort shows what each answer filed and that fynora remembers it`

### Task 3: Phone payoff line

**Files:**
- Create: `mobile/src/lib/quickSortPayoff.ts`, `mobile/src/lib/quickSortPayoff.test.ts`
- Modify: `mobile/src/api/endpoints.ts`, `mobile/src/components/QuickSortPanel.tsx`
- Test: `mobile/src/components/QuickSortPanel.test.tsx`, `mobile/src/components/QuickSortPanel.dashboard.test.tsx`

**Interfaces:**
- Consumes: Task 1's JSON. Mirrors Task 2's `QuickSortAnswerResult` and `payoffLine` signature exactly (copied, since the apps share no package).

- [ ] **Step 1: Write the same four helper tests as Task 2 Step 1** in `mobile/src/lib/quickSortPayoff.test.ts`.

- [ ] **Step 2: Run and see them fail.** Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 node_modules/.bin/jest src/lib/quickSortPayoff.test.ts`

- [ ] **Step 3: Implement the helper and the endpoint type.**

- [ ] **Step 4: Write the failing panel tests:**
  - in `QuickSortPanel.test.tsx`: after an answer, `screen.getByTestId('quick-sort-payoff')` has the remembered text with `fmtCurrency(2400)`, and `AccessibilityInfo.announceForAccessibility` was called once with that text; empty after a skip; shown with the all-sorted message; a rejected answer shows no payoff; `{ filed: 1 }` gives `✓ Filed as Groceries.`
  - in `QuickSortPanel.dashboard.test.tsx`: the line shows after an answer.

- [ ] **Step 5: Run and see them fail.** Run: `NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 node_modules/.bin/jest src/components/QuickSortPanel`

- [ ] **Step 6: Implement in QuickSortPanel.** Add a `payoff` state, set it in `answer` via `payoffLine(result, category, q.payee, fmtCurrency)`, announce it once with `AccessibilityInfo.announceForAccessibility`, and clear it in `skip`. Render `<Text testID="quick-sort-payoff">` only while it is set, under the heading in both variants, using the existing body typography and ink colour.

- [ ] **Step 7: Run the mobile suite, tsc and eslint.**

Run: `NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 node_modules/.bin/jest`, then `npx --yes node@22 node_modules/.bin/tsc --noEmit`, then eslint on the touched files.
Expected: all pass.

- [ ] **Step 8: Commit** `feat(mobile): quick sort shows what each answer filed and that fynora remembers it`

### Task 4: Check it works in the running web app

- [ ] Start the backend and web app locally, seed a waiting payee with three payments, answer it in Quick sort in Chrome, and confirm the line reads as the spec says. Then answer a `cut:` row and confirm "✓ Filed as …". Take a screenshot as evidence. The phone part is checked with the next store build.
