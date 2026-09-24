import type { Query } from '@tanstack/react-query';
import { FINANCIAL_QUERY_KEYS } from './invalidateFinancialData';

/**
 * Who decides what to refetch when the app returns to the foreground.
 *
 * While useChangePolling is watching the backend's change stamp, the stamp is the authority on
 * whether the data it covers changed: it is asked on every return, and only a different answer
 * invalidates anything. If the focus refetch ALSO refetched those queries, a change made elsewhere
 * while the app was backgrounded cost two rounds of reads -- the focus refetch, then the stamp's
 * invalidation of the very same queries -- and an unchanged app re-read data the stamp had just
 * proved current.
 *
 * So the queries the stamp covers opt out of refetch-on-focus (see shouldRefetchOnFocus), and
 * everything else keeps it. The opt-out applies ONLY while the watch is active: signed out, in
 * onboarding, or with the tabs not showing, nothing would refetch them otherwise.
 */

let active = false;

/** Set by useChangePolling while it is enabled. */
export function setChangeWatchActive(value: boolean): void {
  active = value;
}

/** The queries the stamp's change handling invalidates: the financial cascade, profile, categories. */
const COVERED_KEYS: ReadonlySet<string> = new Set<string>([...FINANCIAL_QUERY_KEYS, 'user-settings', 'categories']);

function isSameLocalDay(timestamp: number): boolean {
  return new Date(timestamp).toDateString() === new Date().toDateString();
}

/**
 * The default `refetchOnWindowFocus` for every query (queryClient.ts). Focus itself only ever
 * refetches queries already past their staleTime; this decides which of those may.
 *
 * A covered query last loaded on an EARLIER DAY still refetches: the stamp cannot see midnight, and
 * "this month" totals and Today/Yesterday labels change at the day boundary with no data change.
 * One whose last fetch failed also refetches, since nothing proves it is current.
 */
export function shouldRefetchOnFocus(query: Query): boolean {
  if (!active) return true;
  const key = query.queryKey[0];
  if (typeof key !== 'string' || !COVERED_KEYS.has(key)) return true;
  // A failed load says nothing about whether the data changed, so it gets another go on return.
  if (query.state.status === 'error') return true;
  const loadedAt = query.state.dataUpdatedAt;
  return loadedAt > 0 && !isSameLocalDay(loadedAt);
}
