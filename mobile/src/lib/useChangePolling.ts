import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { changesApi } from '../api/endpoints';
import { invalidateFinancialData } from './invalidateFinancialData';

export const CHANGE_POLL_MS = 30_000;

/**
 * Notices changes made on another device -- the web app renaming the account, a statement imported
 * there -- while this app is open in front of the user, without re-running every screen's query on
 * a timer.
 *
 * Asks the backend for one small opaque stamp (see ChangeStampService) every 30s and compares it
 * with the previous answer. Different means something the app shows changed, so the financial
 * queries (the same set every local write refreshes) and the profile are invalidated: mounted
 * screens refetch now, the rest are marked stale and refetch when opened. The first answer is only
 * a baseline: nothing is refetched unless the stamp moves.
 *
 * Pauses on its own while the app is in the background -- refetchInterval ticks only while React
 * Query considers the app focused (startForegroundRefetch), and coming back to the front polls
 * again straight away. A failed poll is silent: no retry (the next tick is the retry), no error UI.
 *
 * One consequence worth knowing: an edit made on THIS device also changes the stamp, so the next
 * poll refreshes the active screens once more. That is one redundant round of reads, never a wrong
 * screen, and cheaper than trying to tell our own writes from someone else's.
 */
export function useChangePolling(enabled: boolean, intervalMs: number = CHANGE_POLL_MS): void {
  const queryClient = useQueryClient();
  const { data } = useQuery({
    queryKey: ['change-stamp'],
    queryFn: () => changesApi.stamp(),
    enabled,
    refetchInterval: intervalMs,
    refetchIntervalInBackground: false,
    retry: false,
    staleTime: 0,
  });

  const lastStamp = useRef<string | undefined>(undefined);
  const stamp = enabled ? data?.stamp : undefined;

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
      invalidateFinancialData(queryClient);
      void queryClient.invalidateQueries({ queryKey: ['user-settings'] });
    }
    lastStamp.current = stamp;
  }, [enabled, stamp, queryClient]);
}
