import { renderHook } from '@testing-library/react-native';
import { useDashboardKpis } from './useDashboardKpis';

const BASE_SUMMARY = {
  currentBalance: 50000, monthlyIncome: 145000, monthlyExpense: 18672,
  incomeDeltaPct: 12, expenseDeltaPct: -8, netCashFlow: 126328, netDeltaPct: 15,
  savingsRatePct: 87, reportingMonth: '2026-09', reportingMonthIsCurrent: true,
} as const;

describe('useDashboardKpis', () => {
  it('returns an empty kpis array when summary is undefined', () => {
    const { result } = renderHook(() => useDashboardKpis(undefined));
    expect(result.current.kpis).toEqual([]);
  });

  it('separates Total Balance from the other four KPIs into balanceKpi/snapshotKpis', () => {
    const { result } = renderHook(() => useDashboardKpis(BASE_SUMMARY as any));
    expect(result.current.balanceKpi?.label).toBe('Total Balance');
    expect(result.current.snapshotKpis.map((k) => k.label)).toEqual([
      'Income', 'Expenses', 'Net Savings', 'Savings Rate',
    ]);
  });

  it('labels the delta "vs last month" when the reporting month is current', () => {
    const { result } = renderHook(() => useDashboardKpis(BASE_SUMMARY as any));
    expect(result.current.deltaLabel).toBe('vs last month');
  });

  it('names the actual month when the reporting month is not current', () => {
    const { result } = renderHook(() =>
      useDashboardKpis({ ...BASE_SUMMARY, reportingMonthIsCurrent: false, reportingMonth: '2026-07' } as any)
    );
    // monthLabel() formats short month + 2-digit year (see mobile/src/lib/format.ts), not "July 2026".
    expect(result.current.deltaLabel).toBe('vs the month before Jul 26');
  });

  // periodTitleLong is for months named inside a phrase ("June 2026 at a Glance"), where
  // monthLabel's "Jun 26" would read as a date.
  it('titles the period "This Month" only when the reporting month is current', () => {
    const { result } = renderHook(() => useDashboardKpis(BASE_SUMMARY as any));
    expect(result.current.periodTitle).toBe('This Month');
    expect(result.current.periodTitleLong).toBe('This Month');
    expect(result.current.priorPeriodLabel).toBe('last month');
  });

  it('titles a past reporting month by name, and names the month it is compared against', () => {
    const { result } = renderHook(() =>
      useDashboardKpis({
        ...BASE_SUMMARY, reportingMonthIsCurrent: false, reportingMonth: '2026-06', priorMonth: '2026-05',
      } as any)
    );
    expect(result.current.periodTitle).toBe('Jun 26');
    expect(result.current.periodTitleLong).toBe('June 2026');
    expect(result.current.priorPeriodLabel).toBe('May 2026');
  });

  it('still names the comparison when a past reporting month arrives without priorMonth', () => {
    const { result } = renderHook(() =>
      useDashboardKpis({ ...BASE_SUMMARY, reportingMonthIsCurrent: false, reportingMonth: '2026-06' } as any)
    );
    expect(result.current.priorPeriodLabel).toBe('the month before June 2026');
  });

  const savingsRate = (summary: object) =>
    renderHook(() => useDashboardKpis(summary as any)).result.current.snapshotKpis.find((k) => k.label === 'Savings Rate');

  it('keeps a real savings rate with its usual caption', () => {
    expect(savingsRate(BASE_SUMMARY)).toMatchObject({ value: 87, caption: 'Share of income kept' });
  });

  it('carries a withheld savings rate as null with the reason as its caption', () => {
    expect(savingsRate({ ...BASE_SUMMARY, savingsRatePct: null, savingsRateGateReason: 'UNRESOLVED_EXCEEDS_INCOME' }))
      .toMatchObject({ value: null, caption: 'Classify money received to see this' });
    expect(savingsRate({ ...BASE_SUMMARY, savingsRatePct: null, savingsRateGateReason: 'NO_INCOME' }))
      .toMatchObject({ value: null, caption: 'No income counted yet' });
  });

  describe('soFarNote', () => {
    it('names the account and the server\'s own reporting month when a statement stops short of it', () => {
      const { result } = renderHook(() => useDashboardKpis({
        ...BASE_SUMMARY,
        statementBehind: { accountName: 'HDFC Card', statementEnd: '2026-09-11', accountCount: 1 },
      } as any));
      expect(result.current.soFarNote)
        .toBe("September is partial so far: HDFC Card's latest statement ends Sep 11, so its last days aren't in yet.");
    });

    it('uses the month the server reports, not the calendar month', () => {
      const { result } = renderHook(() => useDashboardKpis({
        ...BASE_SUMMARY, reportingMonth: '2026-07', reportingMonthIsCurrent: false,
        statementBehind: { accountName: 'HDFC Card', statementEnd: '2026-07-11', accountCount: 1 },
      } as any));
      expect(result.current.soFarNote).toContain('July is partial so far');
    });

    it('is null when nothing is behind, when an older server omits the field, and before the summary loads', () => {
      expect(renderHook(() => useDashboardKpis({ ...BASE_SUMMARY, statementBehind: null } as any)).result.current.soFarNote).toBeNull();
      expect(renderHook(() => useDashboardKpis(BASE_SUMMARY as any)).result.current.soFarNote).toBeNull();
      expect(renderHook(() => useDashboardKpis(undefined)).result.current.soFarNote).toBeNull();
    });

    it('leaves every figure and delta exactly as it was -- the note labels, it does not withhold', () => {
      const behind = { accountName: 'HDFC Card', statementEnd: '2026-09-11', accountCount: 1 };
      const withNote = renderHook(() => useDashboardKpis({ ...BASE_SUMMARY, statementBehind: behind } as any)).result.current;
      const without = renderHook(() => useDashboardKpis(BASE_SUMMARY as any)).result.current;
      expect(withNote.snapshotKpis).toEqual(without.snapshotKpis);
    });
  });
});
