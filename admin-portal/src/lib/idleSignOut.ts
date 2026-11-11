import { safeStorage } from './safeStorage';

/**
 * Admin portal idle sign-out: 15 minutes with no mouse, keyboard, scroll or touch in any portal tab
 * signs the admin out, after a 60-second warning. Same rule as the customer web app
 * (frontend/src/lib/idleSignOut.ts, design 2026-11-10), with its own clock.
 *
 * The last-activity time lives in localStorage so every open portal tab shares it: activity in one
 * tab keeps all of them alive.
 */

export const IDLE_LIMIT_MS = 15 * 60_000;
export const WARNING_MS = 60_000;
export const LAST_ACTIVITY_KEY = 'finora_admin_last_activity';

// Mouse movement fires dozens of times a second; one write per 5 seconds is plenty for a
// 15-minute limit.
const WRITE_EVERY_MS = 5_000;

// This tab's own last activity. Normally the same as the stored one; it is what keeps the rule
// working when the browser blocks localStorage, where every stored read comes back empty and the
// tabs simply stop sharing a clock.
let lastInThisTab: number | null = null;

function storedActivity(): number | null {
  const raw = safeStorage.getItem(LAST_ACTIVITY_KEY);
  const n = raw === null ? NaN : Number(raw);
  return Number.isFinite(n) ? n : lastInThisTab;
}

/** Records that the admin did something now. Cheap to call on every event. */
export function markActivity(): void {
  const now = Date.now();
  const last = storedActivity();
  if (last !== null && now - last >= 0 && now - last < WRITE_EVERY_MS) return;
  recordActivityNow();
}

/** Records activity now, unthrottled: for a deliberate action such as "Stay signed in". */
export function recordActivityNow(): void {
  const now = Date.now();
  lastInThisTab = now;
  safeStorage.setItem(LAST_ACTIVITY_KEY, String(now));
}

/** Milliseconds since the last recorded activity in any tab, or null when none was ever recorded. */
export function msSinceActivity(): number | null {
  const last = storedActivity();
  return last === null ? null : Date.now() - last;
}

/** Test-only: forget this tab's own clock, as a fresh page load would. */
export function __resetIdleClockForTests(): void {
  lastInThisTab = null;
}
