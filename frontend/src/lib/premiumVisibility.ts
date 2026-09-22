/**
 * Whether the Premium plan is shown anywhere in the UI.
 *
 * Premium is hidden from every screen (owner decision, 2026-09-22) but nothing behind it is
 * removed: the plan row, its entitlements, checkout, referral grants and every code path stay, so
 * bringing it back later needs no code change, just the build variable below. While it is hidden,
 * a person who already holds Premium is shown as Plus (see planDisplay.ts). It is its own module
 * so a test can turn the flag on and keep exercising the Premium paths that still exist -- every
 * consuming test mocks this whole module, so how the flag itself resolves is invisible to them.
 *
 * Why hidden: with Gmail sync and Bank Sync dropped and investments made free, Premium's feature
 * list is just "Everything in Plus" (plans.ts) at a higher price.
 *
 * Read from `VITE_PREMIUM_PLAN_VISIBLE` (unset, the shipped state, means hidden; only the exact
 * string "true" shows it) rather than written as a literal -- CodeQL's js/trivial-conditional
 * flagged every direct use of a hardcoded `= false` here as dead code (11 alerts, #185-196), since
 * a compile-time-constant boolean makes every branch on it provably one-sided. Reading a build
 * variable is opaque to that analysis the same way `lib/features.ts`'s
 * `VITE_GMAIL_SYNC_UI_ENABLED` already is, with no loss of testability: `vi.stubEnv` flips it in a
 * test exactly as easily as editing a literal would. Build-time (Vite inlines it), so a change on
 * Cloudflare Pages needs a redeploy, not just a variable update.
 */
export const PREMIUM_PLAN_VISIBLE = import.meta.env.VITE_PREMIUM_PLAN_VISIBLE === 'true';
