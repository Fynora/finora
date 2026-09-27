import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';

/**
 * Every cached figure an inflow-kind change can move (Plan 2): a choice, a kind's income flag or a
 * forgotten sender changes income, what is "not counted yet", spending (a Refund kind) and the
 * dashboard's cash flow. The web caches queries for 30s (App.tsx), so without this the Dashboard a
 * user returns to still shows the old banner and income.
 */
export const MONEY_FIGURE_KEYS = [
  'dashboard-summary', 'dashboard-range-summary', 'report', 'report-months', 'insights', 'budgets',
  'transactions', 'recent-transactions',
] as const;

export function useInvalidateMoneyFigures() {
  const queryClient = useQueryClient();
  return useCallback(() => {
    MONEY_FIGURE_KEYS.forEach((key) => { void queryClient.invalidateQueries({ queryKey: [key] }); });
  }, [queryClient]);
}
