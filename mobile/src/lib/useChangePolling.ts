import { useEffect, useRef } from 'react';
import { focusManager, useQuery, useQueryClient, type InvalidateOptions, type QueryClient } from '@tanstack/react-query';
import { changesApi } from '../api/endpoints';
import { isCoveredByChangeStamp, setChangeWatchActive } from './changeWatch';
import { invalidateFinancialQueries, onLocalFinancialWrite } from './invalidateFinancialData';

export const CHANGE_POLL_MS = 30_000;

/**
 * Not financial (see invalidateFinancialData.test's NON_FINANCIAL_KEYS), but another device can
 * change both: the profile, and the category list a rename or new category lands in.
 */
function refreshProfileAndCategories(queryClient: QueryClient, options?: InvalidateOptions): void {
  for (const key of ['user-settings', 'categories']) {
    if (options) void queryClient.invalidateQueries({ queryKey: [key] }, options);
    else void queryClient.invalidateQueries({ queryKey: [key] });
  }
}

/**
 * Notices changes made on another device -- the web app renaming the account, a statement imported
 * there -- while this app is open in front of the user, without re-running every screen's query on
 * a timer.
 *
 * Asks the backend for one small opaque stamp (see ChangeStampService) every 30s and compares it
 * with the previous answer. Different means something the app shows changed, so the financial
 * queries (the same set every local write refreshes), the profile and the categories are invalidated: mounted
 * screens refetch now, the rest are marked stale and refetch when opened. The first answer is only
 * a baseline: nothing is refetched unless the stamp moves.
 *
 * The stamp is also what decides, on returning to the app, whether the queries it covers refetch;
 * see changeWatch.ts.
 *
 * Pauses on its own while the app is in the background -- refetchInterval ticks only while React
 * Query considers the app focused (startForegroundRefetch), and coming back to the front polls
 * again straight away. A failed poll is silent: no retry (the next tick is the retry), no error UI.
 *
 * An edit made on THIS device also moves the stamp, and would otherwise look like a change from
 * elsewhere and refresh every screen a second time. So every local write (invalidateFinancialData)
 * first takes a fresh stamp reading and makes it the new baseline; the screens' own refetches then
 * follow, as they always did. The reading is requested before those refetches, so a change made on
 * another device in between still moves the stamp at the next poll rather than being absorbed --
 * except within the few milliseconds the server takes to answer both, where it is picked up at the
 * next change or foreground return instead. If the reading fails (offline), the next poll refreshes
 * once more, as it would have without this.
 */
export function useChangePolling(enabled: boolean, intervalMs: number = CHANGE_POLL_MS): void {
  const queryClient = useQueryClient();
  const { data, isError, errorUpdatedAt, dataUpdatedAt } = useQuery({
    queryKey: ['change-stamp'],
    queryFn: () => changesApi.stamp(),
    enabled,
    refetchInterval: intervalMs,
    refetchIntervalInBackground: false,
    retry: false,
    staleTime: 0,
  });

  const lastStamp = useRef<string | undefined>(undefined);
  // True from the moment the app returns to the front until the stamp answers -- see the fallback.
  const awaitingReturnCheck = useRef(false);
  const stamp = enabled ? data?.stamp : undefined;

  // While watching, the stamp decides what to refetch on return to the app -- see changeWatch.ts.
  useEffect(() => {
    if (!enabled) return undefined;
    setChangeWatchActive(true);
    return () => setChangeWatchActive(false);
  }, [enabled]);

  useEffect(() => {
    if (!enabled) return undefined;
    return focusManager.subscribe((focused) => {
      if (focused) awaitingReturnCheck.current = true;
    });
  }, [enabled]);

  // The stamp answered: the return (if that is what this was) has been checked.
  useEffect(() => {
    awaitingReturnCheck.current = false;
  }, [dataUpdatedAt]);

  // Fallback. Returning to the app leaves the covered queries to the stamp (changeWatch.ts), so if
  // the stamp's answer to THAT return is a failure (a network blip), nothing would refresh them
  // until the next successful poll. Do what the focus refetch would have: refetch the covered
  // queries that are past their staleTime. Only for a failed return check -- a failed ordinary poll
  // does nothing, so an outage of the stamp endpoint cannot turn every poll into a round of reads.
  useEffect(() => {
    if (!enabled || !isError || !awaitingReturnCheck.current) return;
    awaitingReturnCheck.current = false;
    void queryClient.refetchQueries({ type: 'active', stale: true, predicate: isCoveredByChangeStamp });
  }, [enabled, isError, errorUpdatedAt, queryClient]);

  useEffect(() => {
    if (!enabled) return undefined;
    return onLocalFinancialWrite(() => {
      changesApi
        .stamp()
        .then((answer) => {
          lastStamp.current = answer.stamp;
          queryClient.setQueryData(['change-stamp'], answer);
        })
        .catch(() => {
          // Offline or failed: leave the baseline alone; the next poll will refresh once more.
        });
      // The reading above may already include a change made elsewhere a moment ago, and it is now
      // the baseline, so the poll will never report it. The financial cascade the caller runs next
      // does not cover the profile or categories, so refresh those here or nothing would.
      refreshProfileAndCategories(queryClient);
    });
  }, [enabled, queryClient]);

  useEffect(() => {
    // Switched off (signed out, or the tabs are not what is showing). Forgets the last answer so a
    // different account signing in on this device is never compared with the previous one's --
    // sign-out empties the query cache, so the next answer arrives as a fresh baseline. (Switched
    // off and on within one session, the cached answer is still there and is what gets compared.)
    if (!enabled) {
      lastStamp.current = undefined;
      return;
    }
    if (stamp === undefined) return;
    if (lastStamp.current !== undefined && lastStamp.current !== stamp) {
      // cancelRefetch: false -- a fetch already in flight (an earlier-day query the focus just
      // refetched) already started after this change, so restarting it would only read twice.
      invalidateFinancialQueries(queryClient, { cancelRefetch: false });
      refreshProfileAndCategories(queryClient, { cancelRefetch: false });
    }
    lastStamp.current = stamp;
  }, [enabled, stamp, queryClient]);
}
