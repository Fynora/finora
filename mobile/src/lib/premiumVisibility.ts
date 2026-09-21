/**
 * Whether the Premium plan is shown anywhere in the app UI. Mirrors the web app's
 * frontend/src/lib/premiumVisibility.ts, and both are flipped together.
 *
 * Premium is hidden from every screen (owner decision, 2026-09-22) but nothing behind it is
 * removed: the plan, its entitlements, purchase code and referral grants all stay, so bringing it
 * back later is a one-line change here. While hidden, a person who already holds Premium is shown
 * as Plus (see planDisplay.ts). It is its own module so a test can turn the flag on and keep
 * exercising the Premium paths that still exist.
 */
export const PREMIUM_PLAN_VISIBLE = false;
