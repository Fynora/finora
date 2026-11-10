import { safeStorage } from './safeStorage';

/**
 * Web idle sign-out (design 2026-11-10): 15 minutes with no mouse, keyboard, scroll or touch in any
 * Fynora tab signs the user out, after a 60-second warning. The phone app keeps its session behind
 * a PIN instead; this is the browser's own, shorter rule.
 *
 * The last-activity time lives in localStorage so every open tab shares it: activity in one tab
 * keeps all of them alive, and an idle tab reads the same clock as an active one.
 */

export const IDLE_LIMIT_MS = 15 * 60_000;
export const WARNING_MS = 60_000;
export const LAST_ACTIVITY_KEY = 'finora_last_activity';

// Mouse movement fires dozens of times a second; one write per 5 seconds is plenty for a
// 15-minute limit.
const WRITE_EVERY_MS = 5_000;

function storedActivity(): number | null {
  const raw = safeStorage.getItem(LAST_ACTIVITY_KEY);
  if (raw === null) return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

/** Records that the user did something now. Cheap to call on every event. */
export function markActivity(): void {
  const now = Date.now();
  const last = storedActivity();
  if (last !== null && now - last >= 0 && now - last < WRITE_EVERY_MS) return;
  safeStorage.setItem(LAST_ACTIVITY_KEY, String(now));
}

/** Milliseconds since the last recorded activity in any tab, or null when none was ever recorded. */
export function msSinceActivity(): number | null {
  const last = storedActivity();
  return last === null ? null : Date.now() - last;
}
