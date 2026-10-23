import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { aiInsightsApi, insightsApi, peopleApi, recurringApi } from '../api/endpoints';
import { ackEntry, buildToLookAt, isAcked, lookAtRowsFromServer } from './insightsFeed';

/**
 * How many rows Insights' "To look at" shows for the newest month, for the dot on its tab. The same
 * query keys as InsightsScreen and the Dashboard, so the tab costs no extra request once either has
 * loaded. 0 while loading and on any failure: a dot is a promise there is something to see.
 * The server's rows when it gives them (what the screen shows, AI insights spec §8.2), else the
 * phone's own, as on the screen.
 */
export function useInsightsLookAtCount(): number {
  const insightsQ = useQuery({ queryKey: ['insights'], queryFn: () => insightsApi.get() });
  const recurringQ = useQuery({ queryKey: ['recurring'], queryFn: () => recurringApi.list() });
  const changedQ = useQuery({
    queryKey: ['recurring-changed-amounts'], queryFn: () => recurringApi.changedAmounts(), retry: false,
  });
  // Optional, as on an older server: a failure leaves the other rows counted.
  const peopleQ = useQuery({ queryKey: ['people', 'suggestions'], queryFn: () => peopleApi.suggestions(), retry: false });
  const acksQ = useQuery({
    queryKey: ['insight-acknowledgements'], queryFn: () => insightsApi.acknowledgements(), retry: false,
  });
  // InsightsScreen's own key for the newest month, so the two share one request.
  const lookAtQ = useQuery({ queryKey: ['ai-insights', 'look-at', null], queryFn: () => aiInsightsApi.lookAt(), retry: false });
  return useMemo(() => {
    const acks = new Set((acksQ.data ?? []).map((a) => ackEntry(a.kind, a.itemKey)));
    if (lookAtQ.data) return lookAtRowsFromServer(lookAtQ.data.rows).filter((r) => !isAcked(r, acks)).length;
    if (!insightsQ.data && !recurringQ.data) return 0;
    return buildToLookAt({
      insights: insightsQ.data, recurring: recurringQ.data ?? [], changed: changedQ.data ?? [], acks,
      people: peopleQ.data?.items ?? [],
    }).length;
  }, [lookAtQ.data, insightsQ.data, recurringQ.data, changedQ.data, acksQ.data, peopleQ.data]);
}
