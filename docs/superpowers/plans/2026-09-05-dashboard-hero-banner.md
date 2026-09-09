# Dashboard Hero Banner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Dashboard.tsx's existing plain-text greeting ("Good afternoon, Siddharth 👋") an illustrated hero banner — a mountain/sunrise illustration plus a hand-drawn "Small steps today, bigger goals tomorrow" annotation — matching the Dashboard mockup's visual style, without changing any of the greeting's existing text, logic, or data dependencies.

**Architecture:** Pure frontend, presentation-only change to one page. No new API calls, no new state, no new design-system components — this wraps the existing greeting `<div>` in a decorative hero card and adds two `<img>` elements. The two illustration assets were AI-generated this session (see Context below), converted to WebP, and already copied into `frontend/src/assets/dashboard/`.

**Tech Stack:** React 18 + TypeScript, Vite (static asset import), Tailwind, Vitest + `@testing-library/react`.

**Spec:** No separate written spec file. This plan's own "Context" section distills the spec from the Dashboard mockup (`Prototype images/Codex Image 5 Sept 2026, 20_52_34.png`) and the in-session decision to match the live app's graphite/cream palette, not the mockup's purple.

## Context: assets and what's actually being built

Two illustration assets were generated this session and are already in place:
- `frontend/src/assets/dashboard/hero-mountain.webp` (1856×576, 28.9KB) — a flat isometric mountain/sunrise illustration in graphite-to-cream tones, generated to match the live app's actual palette (`#262A33` graphite, `#F4F1EC` cream) rather than the mockup's purple. Has a transparent region on its left side, intended to sit behind/beside the greeting text.
- `frontend/src/assets/dashboard/hero-annotation.webp` (1376×768, 83.6KB) — a single decorative asset combining a hand-drawn arrow doodle and the handwritten text "Small steps today, bigger goals tomorrow", in graphite ink, transparent background.

Both were verified visually after WebP conversion (quality 82/90) to have no compression artifacts and intact transparency — see the file sizes above, down from ~1.1–1.5MB PNG originals.

This plan wraps the *existing* greeting block (`Dashboard.tsx`, currently a plain `<h1>`/`<p>` pair with no visual container) in a `FinoraCard`-style hero section: greeting text on the left, the mountain illustration as a right-side background layer, with the hand-drawn annotation image layered near the top of the illustration. The mockup's separate boxed quote ("— Fynora") is **not** built — that would be new copy/a new UI element beyond wrapping the existing greeting, and is better scoped into a follow-up plan if wanted, rather than added here as a side effect.

Both images are purely decorative (`alt=""`), hidden from screen readers, and hidden below the `lg` breakpoint (`hidden lg:block`) — on a narrow viewport there isn't room for a side illustration without either shrinking the greeting text or overlapping it, and the mockup itself is a desktop-only design.

## Global Constraints

- Palette: the mountain illustration is already graphite/cream per the in-session decision — do not introduce any purple/indigo styling around it (e.g. no purple gradient overlays, no purple card background).
- No new API calls, no new component state — this is a rendering-only change to the JSX Dashboard.tsx already returns from its existing `summary`/`firstName`/`settingsQ` data.
- Both images are decorative: `alt=""`, and the wrapping container gets `aria-hidden="true"` so assistive tech skips the whole illustration, not just individual images missing an accessible name.
- No `Co-Authored-By` / AI-attribution trailer in any commit message (repo-wide rule, `CLAUDE.md`).
- Existing greeting text, `firstName`, `greeting()` time-of-day logic, and the `reportingMonthIsCurrent` conditional sentence must render byte-identical to today — this plan only adds a visual wrapper, it does not touch that logic.

---

## File Structure

- **Modify:** `frontend/src/pages/Dashboard.tsx` — wrap the existing greeting block (currently `Dashboard.tsx`'s `<div className="mb-8">...</div>`) in a new hero card; add two `<img>` elements referencing the already-copied WebP assets.
- **Modify:** `frontend/src/pages/Dashboard.test.tsx` — add one test asserting the hero illustration renders alongside the (unchanged) greeting text.
- **No new files** beyond the two asset files already added to `frontend/src/assets/dashboard/` (not created by this plan's tasks — already in place, verified above).

---

### Task 1: Wrap the greeting in an illustrated hero card

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx`
- Test: `frontend/src/pages/Dashboard.test.tsx`

**Interfaces:**
- Consumes: nothing new from other modules — uses the existing `greeting()`, `firstName`, `summary`, `settingsQ.data?.timezone`, `periodLabel` values already computed earlier in the component (`Dashboard.tsx` lines ~45–318, unchanged).
- Produces: nothing consumed by later tasks — this is the only task in this plan.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/pages/Dashboard.test.tsx`, inside the existing `describe('Dashboard — Financial Health Score', ...)` block (reusing its existing `beforeEach` mocks — no new mock setup needed, this test only checks for the presence of the two new images alongside data that's already mocked there):

```tsx
  it('renders the hero illustration and hand-drawn annotation alongside the greeting', async () => {
    renderDashboard();

    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading.textContent).toMatch(/👋/);

    const heroRegion = document.querySelector('[data-testid="dashboard-hero-illustration"]');
    expect(heroRegion).toBeTruthy();
    expect(heroRegion).toHaveAttribute('aria-hidden', 'true');
    expect(within(heroRegion as HTMLElement).getByTestId('hero-mountain-image')).toBeInTheDocument();
    expect(within(heroRegion as HTMLElement).getByTestId('hero-annotation-image')).toBeInTheDocument();
  });
```

(`within` is already imported at the top of this test file — see the existing `import { render, screen, within, waitFor } from '@testing-library/react';` line.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Dashboard.test.tsx -t "hero illustration"`
Expected: FAIL — `[data-testid="dashboard-hero-illustration"]` doesn't exist yet.

- [ ] **Step 3: Implement the hero card**

Add the two image imports to the top of `frontend/src/pages/Dashboard.tsx`, alongside its other imports:

```tsx
import heroMountain from '../assets/dashboard/hero-mountain.webp';
import heroAnnotation from '../assets/dashboard/hero-annotation.webp';
```

Replace the existing greeting block:

```tsx
      <div className="mb-8">
        <h1 className="text-[26px] font-bold text-ink mb-1">{greeting(settingsQ.data?.timezone)}, {firstName}! 👋</h1>
        <p className="text-muted text-sm">
          Here's what's happening with your finances today.
          {!summary.reportingMonthIsCurrent && summary.reportingMonth && (
            // Not a warning -- reporting on the newest month with data is the intended behaviour.
            // What was missing is that nothing said which month, so the figures read as current.
            <> Your latest figures are from <span className="font-medium text-ink">{periodLabel}</span>.</>
          )}
        </p>
      </div>
```

with:

```tsx
      <div className="relative overflow-hidden bg-card rounded-xl2 border border-border shadow-card mb-8 px-6 py-6 lg:pr-4">
        <div className="relative z-10 lg:max-w-[55%]">
          <h1 className="text-[26px] font-bold text-ink mb-1">{greeting(settingsQ.data?.timezone)}, {firstName}! 👋</h1>
          <p className="text-muted text-sm">
            Here's what's happening with your finances today.
            {!summary.reportingMonthIsCurrent && summary.reportingMonth && (
              // Not a warning -- reporting on the newest month with data is the intended behaviour.
              // What was missing is that nothing said which month, so the figures read as current.
              <> Your latest figures are from <span className="font-medium text-ink">{periodLabel}</span>.</>
            )}
          </p>
        </div>
        {/* Purely decorative -- the illustration and its hand-drawn annotation carry no
            information the text above doesn't already state, so the whole region is hidden from
            assistive tech rather than given (unhelpful, made-up) alt text. */}
        <div
          data-testid="dashboard-hero-illustration"
          aria-hidden="true"
          className="hidden lg:block absolute inset-y-0 right-0 w-[55%]"
        >
          <img
            src={heroMountain}
            alt=""
            data-testid="hero-mountain-image"
            className="absolute inset-0 w-full h-full object-cover object-right"
          />
          <img
            src={heroAnnotation}
            alt=""
            data-testid="hero-annotation-image"
            className="absolute top-4 left-4 w-40 h-auto"
          />
        </div>
      </div>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/Dashboard.test.tsx -t "hero illustration"`
Expected: PASS.

- [ ] **Step 5: Run the full Dashboard test file**

Run: `cd frontend && npx vitest run src/pages/Dashboard.test.tsx`
Expected: every pre-existing test in the file still PASSES — this step exists specifically to catch any pre-existing test that implicitly depended on the greeting's old DOM structure (none were found grepping the file for `mb-8`/the greeting's exact copy during planning, but the plan's own no-guessing standard means this must be confirmed by actually running the suite, not assumed from the grep).

- [ ] **Step 6: Manual verification in the browser**

Run: `cd frontend && npm run dev`, navigate to `/app` (requires a real login — see the note below if one isn't available), and confirm:
- The illustration appears to the right of the greeting on a desktop-width viewport (≥1024px, the `lg` breakpoint), and disappears entirely below it.
- No layout shift or overlap with the greeting text at any width ≥1024px.
- The hand-drawn annotation sits legibly over the mountain illustration, not clipped or overlapping the greeting text.
- Dark mode: toggle the theme and confirm the hero card's background (`bg-card`) switches correctly and the illustration doesn't look jarring against the dark surface (it wasn't designed with a dark variant — note whether this needs a follow-up, don't silently accept a bad-looking result).

If no backend/database is available to actually log in, at minimum confirm via the browser's Elements/Network panel that the two WebP files load with a 200 status and non-zero byte size, and take a screenshot of `ProtectedRoute`'s redirect to confirm no build/runtime errors — the same fallback verification used for the Budgets redesign plan.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/Dashboard.tsx frontend/src/pages/Dashboard.test.tsx frontend/src/assets/dashboard/hero-mountain.webp frontend/src/assets/dashboard/hero-annotation.webp
git commit -m "feat(frontend): add illustrated hero banner to the Dashboard greeting"
```

---

## Self-Review Notes

**Spec coverage:** The one buildable element identified in Context (wrapping the greeting in an illustrated hero card) has its one task. The mockup's separate boxed quote is explicitly called out as not built, with the reason (new copy/UI element, not just a wrapper) rather than silently dropped.

**Placeholder scan:** No "TBD"/"handle edge cases" language — the step 3 code block is the complete implementation, not a sketch.

**Type consistency:** No new types or shared interfaces introduced — this task is self-contained within one component's JSX.

## Next plans in this redesign series

Per the Budgets redesign plan's own roadmap: Goals redesign, an Accounts overview page, and wiring the PDF/bank/XLS collage asset into the Import Statement page, each as their own plan/worktree/PR.
