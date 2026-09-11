# Billing Page Remaining Gaps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the one remaining gap between `frontend/src/pages/Billing.tsx` and the approved mockup that is actually buildable today with no unresolved product decision behind it — an in-app feature-comparison view, linked from both the plan-comparison section and the usage-grid section the mockup shows a link next to.

**Context:** A fresh read of the mockup against the current, shipped `Billing.tsx` (2026-09-11) found 4 gaps. Three of them are **out of scope for this plan** — see "Not planned" below; writing implementation steps for them now would mean guessing at a product decision, not planning one. Mobile's billing screen (`mobile/src/screens/MySubscriptionScreen.tsx`) was checked and is NOT a gap — it's a real, shipped, deliberately-minimal screen by design (own doc comment cites the spec: neither App Store nor Play Store policy allows an in-app cancel button, so it deep-links out; pause/resume are wired to the same backend endpoints the web page uses).

**Architecture:** A new `FeatureComparisonModal` component, local to `Billing.tsx` (matching this file's existing convention of page-local components like `KpiCard`/`KpiEntrance` — see `frontend/src/pages/StatementHistory.tsx`'s own comment on why a single-page-use component isn't extracted to `design-system` yet). Renders `COMPARISON` from `frontend/src/pages/landing/plans.ts` (already the single source of truth for plan/feature data — nothing new to compute or fetch). Follows `design-system/ConfirmDialog.tsx`'s own overlay/focus-trap/Escape-to-close pattern (copied, not imported — `ConfirmDialog` is shaped around a two-button confirm/cancel action, this is a read-only content dialog).

**Tech Stack:** React / TanStack Query is not involved (no new data fetching) / Tailwind (frontend), Vitest + Testing Library (frontend tests).

## Global Constraints

- No new backend endpoint, no new query — `COMPARISON` is a static, already-imported constant.
- One modal, two trigger points (mirrors the mockup's "Compare all features" link near the plan grid and "See all features" link near the usage grid) — do not build two different components.
- No `Co-Authored-By: Claude` trailer in any commit message (repo-wide rule, `CLAUDE.md`).
- This is a docs-and-code worktree — before touching anything, confirm `pwd` shows the worktree path, not the primary checkout.

---

### Task 1: `FeatureComparisonModal` + two trigger links

**Files:**
- Modify: `frontend/src/pages/Billing.tsx`
- Modify: `frontend/src/pages/Billing.test.tsx`

**Interfaces:**
- Consumes: `COMPARISON` from `./landing/plans` (already exported, already used by `frontend/src/pages/landing/Pricing.tsx`).
- Produces: nothing consumed elsewhere — this is a leaf UI addition.

- [x] **Step 1: Write the failing tests first**

In `frontend/src/pages/Billing.test.tsx`, add (following the file's existing `renderPage()`/`subscription()` helper conventions — read a couple of existing tests near the plan-grid coverage first to match style exactly):

```tsx
  it('opens the feature comparison modal from the plan-grid link and closes it on Escape', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: /compare all features/i }));

    expect(await screen.findByRole('dialog', { name: /compare plans/i })).toBeInTheDocument();
    // Spot-check one row from COMPARISON actually renders, not just the dialog chrome.
    expect(screen.getByText('Unlimited accounts')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: /compare plans/i })).not.toBeInTheDocument();
  });

  it('opens the same feature comparison modal from the usage-grid link', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('current-plan-name');

    await user.click(screen.getByRole('button', { name: /see all features/i }));

    expect(await screen.findByRole('dialog', { name: /compare plans/i })).toBeInTheDocument();
  });
```

- [x] **Step 2: Run to verify they fail**

```bash
cd frontend && npx vitest run src/pages/Billing.test.tsx -t "feature comparison"
```
Expected: FAIL — no such button/dialog exists yet.

- [x] **Step 3: Add the `FeatureComparisonModal` component**

In `frontend/src/pages/Billing.tsx`:

Add `COMPARISON` to the existing `landing/plans` import (currently `import { INTENDED_BILLING_CYCLE_KEY, PLANS, priceForCycle } from './landing/plans';`):

```ts
import { COMPARISON, INTENDED_BILLING_CYCLE_KEY, PLANS, priceForCycle } from './landing/plans';
```

Add `Check` is already imported; add `Minus` and `X` to the existing `lucide-react` import block (check the current import list first — `Minus` may already be gone since a prior cleanup PR removed the "static usage tile" flag that used it; add whichever of `Minus`/`X` are not already present).

Add the component, right after `Hero()` and before `export default function Billing()`:

```tsx
/**
 * Read-only content dialog, not a confirm/cancel action -- copies ConfirmDialog's overlay/
 * Escape/focus-trap discipline (design-system/ConfirmDialog.tsx) rather than importing it, since
 * that component's two-button confirm/cancel shape doesn't fit a "just close it" dialog.
 */
function FeatureComparisonModal({ onClose }: { onClose: () => void }) {
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null;
    panelRef.current?.focus();
    return () => previouslyFocused?.focus?.();
  }, []);

  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div
          ref={panelRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="feature-comparison-title"
          tabIndex={-1}
          className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-2xl max-h-[85vh] overflow-y-auto p-5 pointer-events-auto focus:outline-none"
        >
          <div className="flex items-center justify-between mb-4">
            <h3 id="feature-comparison-title" className="font-semibold text-ink">Compare plans</h3>
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border">
                <th className="text-left font-semibold text-muted text-xs uppercase px-2 py-2">Feature</th>
                <th className="px-2 py-2 font-semibold text-ink">Free</th>
                <th className="px-2 py-2 font-semibold text-ink">Plus</th>
                <th className="px-2 py-2 font-semibold text-ink">Premium</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {COMPARISON.map(({ label, free, plus, premium }) => (
                <tr key={label}>
                  <th scope="row" className="text-left font-normal text-ink px-2 py-2.5">{label}</th>
                  <td className="text-center px-2 py-2.5">
                    {free ? <Check size={16} className="inline text-success" /> : <Minus size={16} className="inline text-border" />}
                  </td>
                  <td className="text-center px-2 py-2.5">
                    {plus ? <Check size={16} className="inline text-success" /> : <Minus size={16} className="inline text-border" />}
                  </td>
                  <td className="text-center px-2 py-2.5">
                    {premium ? <Check size={16} className="inline text-success" /> : <Minus size={16} className="inline text-border" />}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
```

- [x] **Step 4: Add the modal's open/close state and the two trigger links**

Inside `export default function Billing()`, add alongside the other `useState` calls near the top (e.g. right after `confirmingCancelPendingOrder`):

```ts
  const [showFeatureComparison, setShowFeatureComparison] = useState(false);
```

In the "How you're using {isFree ? 'Fynora' : 'Premium'}" section header (currently a bare `<h2>` with no trailing link), change:

```tsx
        <h2 className="text-sm font-semibold text-ink mb-3">How you're using {isFree ? 'Fynora' : 'Premium'}</h2>
```

to:

```tsx
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-ink">How you're using {isFree ? 'Fynora' : 'Premium'}</h2>
          <button type="button" onClick={() => setShowFeatureComparison(true)} className="text-xs font-medium text-primary hover:underline">
            See all features →
          </button>
        </div>
```

In the "Choose the plan that's right for you" section header, change:

```tsx
        <div className="flex items-center justify-between mb-3 flex-wrap gap-3">
          <h2 className="text-sm font-semibold text-ink">Choose the plan that's right for you</h2>
          <div className="inline-flex items-center gap-1 bg-bg border border-border rounded-lg p-1">
```

to:

```tsx
        <div className="flex items-center justify-between mb-3 flex-wrap gap-3">
          <h2 className="text-sm font-semibold text-ink">Choose the plan that's right for you</h2>
          <div className="flex items-center gap-3 flex-wrap">
            <button type="button" onClick={() => setShowFeatureComparison(true)} className="text-xs font-medium text-primary hover:underline">
              Compare all features →
            </button>
            <div className="inline-flex items-center gap-1 bg-bg border border-border rounded-lg p-1">
```

(Note the extra opening `<div>` — the existing Monthly/Yearly toggle `<div>` that followed needs its own matching closing `</div>` added right after it, before this section's closing `</div>`. Read the surrounding JSX carefully before editing so the tag nesting stays balanced — do not just append text; verify with `npx tsc -b` after this step, a mismatched JSX tag fails to compile immediately.)

At the very end of the component's returned JSX, right after the existing `{confirmingCancelPendingOrder && (...)}` block and before the closing `</div>`, add:

```tsx
      {showFeatureComparison && <FeatureComparisonModal onClose={() => setShowFeatureComparison(false)} />}
```

- [x] **Step 5: Run to verify the new tests pass**

```bash
cd frontend && npx vitest run src/pages/Billing.test.tsx
```
Expected: all PASS, including the two new tests and every pre-existing one (check nothing else in this file queries by a role/name this change might collide with — the plan grid already has several buttons, so `getByRole('button', { name: /compare all features/i })` must resolve to exactly one element).

- [x] **Step 6: Type-check and lint**

```bash
cd frontend && npx tsc -b 2>&1 | grep -v "functions/" | grep -v "App.test.tsx"
cd frontend && npx eslint src/pages/Billing.tsx src/pages/Billing.test.tsx --max-warnings 0
```
Expected: both clean. If `tsc` reports an unused `Minus` or `X` import, one of them was already present before this task — check the real diff rather than assuming which.

- [x] **Step 7: Run the full frontend suite for regressions**

```bash
cd frontend && npx vitest run
```
Expected: all pass (128 files / ~1200+ tests as of 2026-09-11 — check the actual count printed, don't assume it matches this plan's stale snapshot).

- [x] **Step 8: Manually verify in the browser**

Start the frontend dev server, open Billing & Membership, click both "See all features" and "Compare all features", confirm the same modal opens each time, the table matches the public Pricing page's own comparison table content, Escape and the backdrop both close it, and focus returns to the link that opened it. This is a UI change — an automated pass alone isn't sufficient per this repo's verification standard.

- [x] **Step 9: Commit**

```bash
git add frontend/src/pages/Billing.tsx frontend/src/pages/Billing.test.tsx
git commit -m "feat(billing): add an in-app Compare Plans modal, linked from two places on the page"
```

---

## Not planned — needs a decision first, not an implementation plan

Writing TDD steps for any of these now would mean guessing at a product or architecture decision nobody has made yet. Flagging each with what's actually blocking it:

1. **Referral reward ₹ amounts** ("Total Earned ₹1,250" / "Pending Rewards ₹250" are still hardcoded). Blocked on: this was a **deliberate scope cut** (referral MVP descope, 2026-09-05) — `referralsApi.mine()` intentionally returns only `{code, referralCount}`, no wallet/reward-amount concept. Building this means first deciding what a reward actually IS (fixed ₹ per referral? tiered? capped?) and how it's redeemed — a product conversation with Sid, not an engineering task.
2. **"Premium Value Received" / "Premium Benefits Summary" ₹8,450.** Blocked on: no formula for "value unlocked" has ever been decided — the ₹8,450 and its per-feature breakdown are illustrative numbers from the original mockup, not a computation anyone has specified. Needs Sid to decide what this number should actually mean (e.g. sum of what each unlocked feature would cost standalone? something else?) before any implementation plan makes sense.
3. **Multiple saved payment methods / "Add Payment Method"** (mockup shows a card list with a Default badge and an add-card flow). Blocked on: the current architecture is subscription-mandate-centric, not a customer wallet — `Subscription` has singular `cardLast4`/`cardNetwork`/`cardType` fields tied to the one active Razorpay mandate (confirmed by reading `backend/src/main/java/com/finora/entity/Subscription.java` and `BillingDtos.java` directly, 2026-09-11), not a list of saved cards. Supporting multiple cards needs Razorpay's Customer + saved-card/token APIs, which nothing in this codebase integrates with today — that's a real backend design question deserving its own spec doc (same process `docs/superpowers/specs/2026-09-08-billing-auto-renew-resume-design.md` went through), not something to plan from a mockup screenshot.

## Post-plan checklist (do not skip)

- [x] Re-run the full frontend suite once, not just `Billing.test.tsx`: `cd frontend && npx vitest run`.
- [x] Confirm `git log --oneline origin/main..HEAD` only contains the one commit from this plan before opening a PR.
- [x] Open the PR against `origin/main` from this worktree's branch — do not touch the primary checkout.
