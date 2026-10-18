import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { insightsApi, recurringApi } from '../api/endpoints';
import { ackEntry, buildToLookAt } from './insightsFeed';

/**
 * How many rows Insights' "To look at" shows for the newest month, for the dot on its tab. The same
 * query keys as InsightsScreen and the Dashboard, so the tab costs no extra request once either has
 * loaded. 0 while loading and on any failure: a dot is a promise there is something to see.
 */
export function useInsightsLookAtCount(): number {
  const insightsQ = useQuery({ queryKey: ['insights'], queryFn: () => insightsApi.get() });
  const recurringQ = useQuery({ queryKey: ['recurring'], queryFn: () => recurringApi.list() });
  const changedQ = useQuery({
    queryKey: ['recurring-changed-amounts'], queryFn: () => recurringApi.changedAmounts(), retry: false,
  });
  const acksQ = useQuery({
    queryKey: ['insight-acknowledgements'], queryFn: () => insightsApi.acknowledgements(), retry: false,
  });
  return useMemo(() => {
    if (!insightsQ.data && !recurringQ.data) return 0;
    const acks = new Set((acksQ.data ?? []).map((a) => ackEntry(a.kind, a.itemKey)));
    return buildToLookAt({
      insights: insightsQ.data, recurring: recurringQ.data ?? [], changed: changedQ.data ?? [], acks,
    }).length;
  }, [insightsQ.data, recurringQ.data, changedQ.data, acksQ.data]);
}
