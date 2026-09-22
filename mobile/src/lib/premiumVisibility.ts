/**
 * Whether the Premium plan is shown anywhere in the app UI. Mirrors the web app's
 * frontend/src/lib/premiumVisibility.ts, and both are flipped together.
 *
 * Premium is hidden from every screen (owner decision, 2026-09-22) but nothing behind it is
 * removed: the plan, its entitlements, purchase code and referral grants all stay, so bringing
 * back everything gated on this flag needs no code change here, just the build variable below plus
 * a new build/OTA update -- NOT the whole story, though: the web app's Terms, Refund Policy, Trust
 * & Security, Your Data and Help page were separately rewritten (#1697) to stop mentioning Premium,
 * and flipping this flag does not touch them (mobile has no equivalent legal pages of its own to
 * check, but see the web module's own comment before assuming the flag alone is enough). While
 * hidden, a person who already holds Premium is shown as Plus (see planDisplay.ts). It is its own
 * module so a test can turn the flag on and keep exercising the Premium paths that still exist --
 * every consuming test mocks this whole module, so how the flag itself resolves is invisible to
 * them.
 *
 * Read from `EXPO_PUBLIC_PREMIUM_PLAN_VISIBLE` (unset, the shipped state, means hidden; only the
 * exact string "true" shows it) rather than written as a literal -- CodeQL's js/trivial-conditional
 * flagged every direct use of a hardcoded `= false` here as dead code (11 alerts, #185-196), since
 * a compile-time-constant boolean makes every branch on it provably one-sided. Reading a build
 * variable is opaque to that analysis, the same way the web app's own flag already is, with no loss
 * of testability. `EXPO_PUBLIC_` is this project's existing prefix for a client-embeddable Expo
 * build variable (see EXPO_PUBLIC_LOGODEV_TOKEN).
 */
export const PREMIUM_PLAN_VISIBLE = process.env.EXPO_PUBLIC_PREMIUM_PLAN_VISIBLE === 'true';
