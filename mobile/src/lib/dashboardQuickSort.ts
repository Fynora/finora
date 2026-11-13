/**
 * The Dashboard's few Quick sort questions (spec docs/superpowers/specs/2026-11-13-dashboard-quick-sort-design.md).
 *
 * "Not now" hides the card until the next local calendar day on this device. The stored value names
 * the account it was pressed for (its email, matching the web app, whose profile carries no user id),
 * so a second account signed in here is not hidden by the first one's choice.
 *
 * The web app keeps an identical copy in frontend/src/lib/dashboardQuickSort.ts; the apps share no code.
 */

export const DASHBOARD_QUICK_SORT_KEY = 'finora_dashboard_quick_sort_hidden';

/** Questions asked per Dashboard visit. */
export const DASHBOARD_QUESTION_LIMIT = 3;

/** YYYY-MM-DD of the local calendar date, so "tomorrow" starts at local midnight. */
export function localDateKey(now: Date): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/** What "Not now" stores for this account today. */
export function notNowValue(email: string | null, now: Date): string {
  return `${(email ?? '').toLowerCase()}|${localDateKey(now)}`;
}

/** Whether "Not now" was pressed for this account earlier today. */
export function isHiddenToday(stored: string | null, email: string | null, now: Date): boolean {
  return stored === notNowValue(email, now);
}
