import { useEffect, useRef } from 'react';
import { focusManager, onlineManager, useQuery, useQueryClient } from '@tanstack/react-query';
import { isCoveredByChangeStamp } from './changeWatch';
import { applyPollAnswer, fetchStamp, refreshChanged, setChangeWatchActive } from './changeSync';

export const CHANGE_POLL_MS = 30_000;

/**
 * Notices changes made on another device -- the web app renaming the account, a statement imported
 * there -- while this app is open in front of the user, without re-running every screen's query on
 * a timer.
 *
 * Asks the backend for its change stamp (one opaque value per kind of data) every 30s and hands
 * each answer to lib/changeSync.ts, which compares it with what it saw last and refreshes exactly
 * the kinds of data that moved. The first answer is only a baseline. changeSync.ts also explains how
 * the app's own edits are kept from looking like changes made elsewhere.
 *
 * The stamp is also what decides, on returning to the app, whether the data it covers refetches;
 * see changeWatch.ts.
 *
 * Pauses on its own while the app is in the background -- refetchInterval ticks only while React
 * Query considers the app focused (startForegroundRefetch), and coming back to the front polls
 * again straight away. A failed poll is silent: no retry (the next tick is the retry), no error UI.
 */
export function useChangePolling(enabled: boolean, intervalMs: number = CHANGE_POLL_MS): void {
  const queryClient = useQueryClient();
  const { data, isError, errorUpdatedAt, dataUpdatedAt } = useQuery({
    queryKey: ['change-stamp'],
    queryFn: fetchStamp,
    enabled,
    refetchInterval: intervalMs,
    refetchIntervalInBackground: false,
    retry: false,
    staleTime: 0,
  });

  // True from the moment the app returns to the front until the stamp answers -- see the fallback.
  const awaitingReturnCheck = useRef(false);

  // While watching, the stamp decides what to refetch on return to the app -- see changeWatch.ts.
  useEffect(() => {
    if (!enabled) return undefined;
    setChangeWatchActive(true);
    return () => setChangeWatchActive(false);
  }, [enabled]);

  // Coming back online is a return too (the stamp check runs then, and the covered queries leave
  // their own reconnect refetch to it just as they leave the focus refetch).
  useEffect(() => {
    if (!enabled) return undefined;
    const unsubscribeFocus = focusManager.subscribe((focused) => {
      if (focused) awaitingReturnCheck.current = true;
    });
    const unsubscribeOnline = onlineManager.subscribe((online) => {
      if (online) awaitingReturnCheck.current = true;
    });
    return () => {
      unsubscribeFocus();
      unsubscribeOnline();
    };
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
    if (!enabled || !data) return;
    const changed = applyPollAnswer(data);
    if (changed.length > 0) refreshChanged(queryClient, changed);
  }, [enabled, data, queryClient]);
}
