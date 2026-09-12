import { useMemo } from 'react';
import type { KpiItem } from '../components/dashboard/LedgerSnapshotCard';
import { monthLabel } from './format';
import type { DashboardSummary } from '../types';

// Bug 05, mobile side. These KPIs are the newest month the account has DATA for, which for a
// product built around importing statements in arrears is routinely not the current calendar
// month. This screen asserted "vs last month" over whichever month that happened to be, exactly
// as the web dashboard did. The backend now says which month it is reporting on; both clients
// read it rather than guessing, which is the drift check-client-auth-policy.py exists to catch
// in the auth layer and which this is the reporting-layer instance of.
// summary can still be undefined here -- callers with a still-loading first fetch get these off
// default values below.
export function useDashboardKpis(summary: DashboardSummary | undefined) {
  return useMemo(() => {
    const periodIsCurrent = summary ? (summary.reportingMonthIsCurrent || !summary.reportingMonth) : true;
    const periodLabel = periodIsCurrent ? 'this month' : monthLabel(summary!.reportingMonth!);
    const deltaLabel = periodIsCurrent
      ? 'vs last month'
      : `vs the month before ${monthLabel(summary!.reportingMonth!)}`;
    const deltaSpokenLabel = periodIsCurrent
      ? 'versus last month'
      : `versus the month before ${monthLabel(summary!.reportingMonth!)}`;

    const kpis: KpiItem[] = summary
      ? [
          {
            label: 'Total Balance', value: summary.currentBalance, delta: null as number | null, invert: false,
            // Track C/C5. Total Balance is a STOCK (Account.balance right now), not a flow this
            // reporting period describes, so it has no month-over-month % to put in the same slot
            // the other three KPIs use -- what belongs there instead is when the number was last
            // touched. Account.balance only moves when a transaction posts, so if the newest one on
            // file is from a past month, this figure is only as fresh as that: reuses the exact
            // periodIsCurrent/reportingMonth this hook already computes for the identical reason
            // (Bug 05) rather than inventing a second "how current is this" concept.
            caption: periodIsCurrent ? 'As of today' : `As of ${monthLabel(summary.reportingMonth!)}`,
            isPercent: false,
          },
          { label: 'Income', value: summary.monthlyIncome, delta: summary.incomeDeltaPct, invert: false, caption: null as string | null, isPercent: false },
          { label: 'Expenses', value: summary.monthlyExpense, delta: summary.expenseDeltaPct, invert: true, caption: null as string | null, isPercent: false },
          { label: 'Net Savings', value: summary.netCashFlow, delta: summary.netDeltaPct, invert: false, caption: null as string | null, isPercent: false },
          // Web's identical 5th KPI (Dashboard.tsx:382) -- a stock-like ratio, not a currency amount,
          // so it skips AnimatedNumber (hard-wired to fmtCurrency -- see that component's own
          // worklet) the same way Total Balance skips a month-over-month delta: not every KPI on
          // this grid is shaped the same as the other three.
          //
          // No backend field for a month-over-month savings-rate delta exists (checked
          // DashboardSummary) -- a static, honest caption instead of a fabricated percentage,
          // same pattern Total Balance's own caption uses for the same reason (a real number isn't
          // available, so the row explains itself in words instead of inventing one).
          { label: 'Savings Rate', value: summary.savingsRatePct, delta: null as number | null, invert: false, caption: 'Share of income kept', isPercent: true },
        ]
      : [];

    // Monthly Snapshot (2x2) gets 4 of the 5 KPIs; Total Balance moves into its own card,
    // matching the redesign mockup -- same figure, same "As of today"/"As of <month>" caption,
    // just a different card.
    const balanceKpi = kpis.find((k) => k.label === 'Total Balance') ?? null;
    const snapshotKpis = kpis.filter((k) => k.label !== 'Total Balance');

    return { kpis, balanceKpi, snapshotKpis, periodIsCurrent, periodLabel, deltaLabel, deltaSpokenLabel };
  }, [summary]);
}
