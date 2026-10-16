import { useQuery } from '@tanstack/react-query';
import { categoriesApi, insightsApi, reportsApi } from '../api/endpoints';

const FIVE_MINUTES = 5 * 60_000;

/**
 * What the Reports screen shows beyond the month's own figures: the user's categories (icons and
 * colours), the insights for that month, and the last six months. Each is optional by design: a
 * failure leaves its section out and never touches the report itself. Query keys start with ones
 * `lib/invalidateFinancialData` already clears, so a write anywhere refreshes these too, and
 * `['categories']` / `['insights', ...]` share their cache with the screens that already load them.
 *
 * Keyed by month, so an answer for a month the reader has left lands in its own cache entry and is
 * never shown against another month's report.
 */
export function useReportExtras(month: string | null, previousMonth: string | null = null) {
  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.list(),
    staleTime: FIVE_MINUTES,
  });
  const insights = useQuery({
    queryKey: ['insights', 'reports', month],
    queryFn: () => insightsApi.get(month as string),
    enabled: month !== null,
    staleTime: FIVE_MINUTES,
    retry: false,
  });
  const trend = useQuery({
    queryKey: ['report', 'trend', month],
    queryFn: () => reportsApi.trend(month as string, 6),
    enabled: month !== null,
    staleTime: FIVE_MINUTES,
    retry: false,
  });
  // The month before, for the category changes. Under the report's own key, so a month the Dashboard
  // or this screen already loaded costs nothing. Asked for only once the report on screen says its
  // comparison with that month can be trusted (the caller passes null until then).
  const previous = useQuery({
    queryKey: ['report', previousMonth],
    queryFn: () => reportsApi.forMonth(previousMonth as string),
    enabled: previousMonth !== null,
    staleTime: FIVE_MINUTES,
    retry: false,
  });
  return {
    categories: categories.data ?? [],
    // Only ever the month asked for: if the server described another one, it is not that month's.
    previous: previousMonth !== null && previous.data?.month === previousMonth ? previous.data : null,
    // The server also echoes the month it described; if it answered for a different one (no
    // spending in the requested month), it is not this month's.
    insights: insights.data && insights.data.reportingMonth === month ? insights.data : null,
    trend: trend.data ?? null,
  };
}
