import type { Query } from '@tanstack/react-query';
import { COVERED_KEYS, isChangeWatchActive } from './changeSync';

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
 * (If the stamp's answer to a return is a failure, useChangePolling falls back to refetching the
 * stale covered queries itself, so a network blip does not leave them waiting for the next poll.)
 *
 * So the queries the stamp covers opt out of refetch-on-focus (see shouldRefetchOnFocus), and
 * everything else keeps it. The opt-out applies ONLY while the watch is active: signed out, in
 * onboarding, or with the tabs not showing, nothing would refetch them otherwise.
 */

/** True for the queries the stamp's change handling covers (see COVERED_KEYS). */
export function isCoveredByChangeStamp(query: Query): boolean {
  const key = query.queryKey[0];
  return typeof key === 'string' && COVERED_KEYS.has(key);
}

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
  if (!isChangeWatchActive()) return true;
  if (!isCoveredByChangeStamp(query)) return true;
  // A failed load says nothing about whether the data changed, so it gets another go on return.
  if (query.state.status === 'error') return true;
  const loadedAt = query.state.dataUpdatedAt;
  return loadedAt > 0 && !isSameLocalDay(loadedAt);
}
