# Mobile parity for the Bank Correction badge — scope

Status: scoping. Grounded in the real mobile code (`mobile/src/screens/LedgerScreen.tsx`,
`mobile/src/components/TransactionExplanationModal.tsx`, `mobile/src/api/endpoints.ts`,
`mobile/src/types/index.ts`) read fresh for this doc, not assumed from the web implementation.

## Goal

Plan 6 Track B (#1456) shipped `pendingBankCorrection`, the "Bank Correction" badge, and the
acknowledge action on **web only**. Mobile's own `Transaction` type, `LedgerScreen.tsx`, and every
other mobile screen have zero references to any of it — a flagged correction is currently invisible
to a mobile user; the row just looks like an ordinary transaction. This scopes bringing mobile to
parity.

**No backend work is needed.** `POST /transactions/{id}/acknowledge-bank-correction` and
`GET /transactions/{id}/correction-history` are plain REST endpoints already serving any client;
`pendingBankCorrection` is already in the OpenAPI schema mobile's own `generated-types.ts` picked up
when Track B regenerated it (mobile/src/api/generated-types.ts, purely additive, never consumed by
mobile's hand-written code). This is UI-only work.

## What already exists to build on

Mobile already ported one close cousin of this exact feature — the "Why this category?" panel
(Phase 4) — as `TransactionExplanationModal.tsx`, explicitly documented as "ported from
frontend/src/pages/Ledger.tsx's ExplanationModal. Same plain Modal + Card + backdrop shape and same
lazy `enabled`-gated useQuery keyed by id... the closest existing precedent for an on-demand detail
panel in this codebase." That is the template this doc reuses, not a new pattern.

`LedgerScreen.tsx`'s own `statusBadges()` function is *already* documented as the "Mobile equivalent
of the web's identical statusBadges" — `needsCategoryReview`/`recurring`/`categoryManuallySet` all
render there today. `pendingBankCorrection` is the next boolean in that exact same family, not a new
concept for this screen.

## The one real design difference from web, and why it changes the plan

Web's badges are individually tappable buttons (`<motion.button>` per badge) — clicking "Bank
Correction" directly opens the detail modal. **Mobile's badges are plain, non-interactive `<Text>`
pills.** The whole row is one `Pressable` (tap → recategorize, long-press → delete), and every other
per-row action (source info, edit, explain, mark/unmark transfer) instead gets its own small nested
icon `Pressable` (`accessible={false}`, `hitSlop={8}`, `styles.sourceButton`) plus a matching entry
in the row's own `accessibilityActions` array — the real reachable path for a screen-reader user,
since the nested icon buttons are deliberately not independent VoiceOver/TalkBack stops.

So porting this literally (making the badge itself tappable) would be inconsistent with every other
interactive affordance already on this row. The right mobile shape, matching the established
convention exactly:

- The "Bank Correction" text pill still renders in `statusBadgeRow`, same as `needsCategoryReview`/
  `recurring` — visual parity, not interactive.
- A **new dedicated icon button** (only rendered when `t.pendingBankCorrection`), styled and wired
  exactly like the existing source/edit/explain icons, opens the new detail modal.
- A **new `accessibilityAction`** entry (e.g. `'viewCorrection'`) on the row, so a screen-reader user
  reaches it the same way they reach every other row action today.

## In scope

1. **Types** (`mobile/src/types/index.ts`) — add `pendingBankCorrection: boolean` to `Transaction`
   (mirrors web's `types/index.ts` addition exactly) and a `BankCorrectionHistoryEntry` type
   (`action: string`, `metadata: Record<string, unknown>`, `createdAt: string`).
2. **API client** (`mobile/src/api/endpoints.ts`) — add `acknowledgeBankCorrection(id)` and
   `correctionHistory(id)` to `transactionsApi`, matching the exact shape of the neighboring
   `confirmNotDuplicate`/`markTransfer` entries already there.
3. **`statusBadges()` + its color mapping** (`LedgerScreen.tsx`) — add the "Bank Correction" entry.
   Requires widening the tone union: `statusBadges`'s return type and the row's own local
   `badgeToneColors` map are currently typed `'warning' | 'primary' | 'success'` only (no `'danger'`)
   — the row's *other* badge, `reconciliationBadge`, already has a full `danger` mapping
   (`{ bg: c.dangerBg, fg: c.danger }`) that `statusBadges`' badges never needed until now. Widen
   both to include `'danger'` and add that one entry to `badgeToneColors`.
4. **A new icon button + `BankCorrectionModal`** — new component
   `mobile/src/components/BankCorrectionModal.tsx`, structured like
   `TransactionExplanationModal.tsx`: `Modal` + `Card` + `useQuery` keyed by transaction id, fetching
   `correctionHistory`, rendering each entry's action label and (when present) old-vs-new
   amount/narration, plus a `Button` at the bottom calling `acknowledgeBankCorrection` and closing on
   success (mirroring web's `BankCorrectionModal` from #1456, adapted to mobile's Modal/Card/Button
   primitives instead of web's div/Badge/Button).
5. **Row wiring** (`LedgerScreen.tsx`) — the new icon button (only when `t.pendingBankCorrection`),
   the new `accessibilityAction` entry + its `onAccessibilityAction` branch, `useState` for which
   transaction's correction is being viewed, and rendering `<BankCorrectionModal>` alongside the
   screen's other modals at the bottom of the JSX tree.
6. **Tests** — extend `LedgerScreen.test.tsx` (mirrors the existing `needsCategoryReview` badge
   tests: badge renders/doesn't render, icon button opens the modal) and add
   `BankCorrectionModal.test.tsx` (mirrors whatever test file backs `TransactionExplanationModal`,
   if one exists — confirm and follow its exact shape rather than inventing a new convention).

## Out of scope

- **Any other mobile screen.** `DashboardScreen`/`CategoryReviewScreen` reference
  `needsCategoryReview` per the earlier grep, but Track B's badge is Ledger-only on web too — no
  reason to add it anywhere web doesn't have it.
- **Backend changes of any kind** — confirmed above, none needed.
- **Push notifications or any proactive alerting** for a new correction. Web has none either
  (the user discovers it by opening Ledger); mobile shouldn't invent a notification-worthy signal
  the web experience doesn't already treat as one.
- **Offline/retry handling beyond whatever `acknowledgeBankCorrection`'s neighbors already do** —
  match `markTransfer`'s existing error-handling shape exactly, don't design a new one.

## Decisions still needed (small, but real)

1. **Icon choice for the new button.** The existing icons are semantically named
   (`information-circle-outline`, `pencil-outline`, `help-circle-outline`, `swap-horizontal`) —
   `alert-circle-outline` (matches the danger tone, distinguishes from the neutral
   information-circle already in use) is the natural fit, but this is Sid's call, not mine to
   settle unilaterally in an implementation plan.
`mobile/src/components/TransactionExplanationModal.test.tsx` exists — confirmed, not assumed — so
`BankCorrectionModal.test.tsx` follows its exact shape directly; no open question there.

## Testing approach

Same discipline as every other mobile port in this codebase: extend the existing screen test file
for the row-level behavior (badge presence, icon button, accessibility action), and a dedicated test
file for the new modal component (loading/error/success states, the acknowledge action clearing the
flag). No new backend test needed — Track B's own backend tests already cover both endpoints for
every client.

## Readiness

This is meaningfully smaller than Track B itself: no product decisions, no unresolved technical
identity questions, no new backend surface — it's porting an already-decided, already-shipped web
feature onto an existing, well-established mobile pattern (`TransactionExplanationModal` and its
row's icon-button convention). Ready to move directly into an implementation plan once the icon
choice above is confirmed.
