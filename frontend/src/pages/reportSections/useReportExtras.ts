import { useEffect, useState } from 'react';
import {
  categoriesApi, insightsApi, reportsApi, type CategoryOption, type InsightsData, type ReportTrendPoint,
} from '../../api/endpoints';

/**
 * What the Reports screen shows beyond the month's own figures: the user's categories (icons and
 * colours), the insights for that month, and the last six months. Each is optional by design. A
 * failure leaves its section out and never touches the report itself, and a response that arrives
 * after the reader has moved to another month is dropped rather than shown under the wrong month.
 */
export function useReportExtras(month: string) {
  const [categories, setCategories] = useState<CategoryOption[]>([]);
  const [insights, setInsights] = useState<{ month: string; data: InsightsData | null } | null>(null);
  const [trend, setTrend] = useState<{ month: string; data: ReportTrendPoint[] | null } | null>(null);

  useEffect(() => {
    let stale = false;
    categoriesApi.list().then((list) => { if (!stale) setCategories(list); }).catch(() => {});
    return () => { stale = true; };
  }, []);

  useEffect(() => {
    if (!month) return;
    let stale = false;
    insightsApi.get(month)
      .then((data) => { if (!stale) setInsights({ month, data }); })
      .catch(() => { if (!stale) setInsights({ month, data: null }); });
    reportsApi.trend(month, 6)
      .then((data) => { if (!stale) setTrend({ month, data }); })
      .catch(() => { if (!stale) setTrend({ month, data: null }); });
    return () => { stale = true; };
  }, [month]);

  return {
    categories,
    // Only ever the selected month's. The server also echoes the month it described; if it
    // answered for a different one (no spending in the requested month), it is not this month's.
    insights: insights?.month === month && insights.data?.reportingMonth === month ? insights.data : null,
    trend: trend?.month === month ? trend.data : null,
  };
}
