/**
 * Whether the Premium plan is shown anywhere in the UI.
 *
 * Premium is hidden from every screen (owner decision, 2026-09-22) but nothing behind it is
 * removed: the plan row, its entitlements, checkout, referral grants and every code path stay, so
 * bringing it back later is a one-line change here. While it is hidden, a person who already holds
 * Premium is shown as Plus (see planDisplay.ts). It is its own module so a test can turn the flag
 * on and keep exercising the Premium paths that still exist.
 *
 * Why hidden: with Gmail sync and Bank Sync dropped and investments made free, Premium's feature
 * list is just "Everything in Plus" (plans.ts) at a higher price.
 */
export const PREMIUM_PLAN_VISIBLE = false;
