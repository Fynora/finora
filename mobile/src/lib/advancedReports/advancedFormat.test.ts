import { fmtCurrency as fmt } from '../format';
import type { AdvancedComparison, AdvancedPeriodInfo } from '../../api/endpoints';
import {
  againstName, compactAmount, coverageNote, drillFilter, gateNote, incompleteNote, leftOutNote, monthBounds,
  monthYear, noDataCopy, percentChange, periodSpan, pointsChange, signedAmount,
} from './advancedFormat';

const period = (over: Partial<AdvancedPeriodInfo> = {}): AdvancedPeriodInfo => ({
  hasData: true, noDataReason: null, range: 'LAST_12_MONTHS', startMonth: '2025-10', endMonth: '2026-09',
  monthCount: 12, requestedMonths: 12, firstDataMonth: '2024-01', newestDataMonth: '2026-09',
  leftOutMonth: null, leftOutReason: null, incompleteMonths: [], compare: 'PREVIOUS_PERIOD',
  compareOptions: ['PREVIOUS_PERIOD'], previousStartMonth: '2024-10', previousEndMonth: '2025-09', ...over,
});

const cmp = (over: Partial<AdvancedComparison> = {}): AdvancedComparison => ({
  reliable: false, gateReason: null, previousIncome: null, previousExpense: null, previousNet: null,
  previousAvgMonthlyExpense: null, previousSavingsRatePct: null, incomeDeltaPct: null, expenseDeltaPct: null,
  netDelta: null, avgMonthlyExpenseDeltaPct: null, savingsRateDeltaPp: null, ...over,
});


describe('period wording', () => {
  it('names the months a period spans, and a single month once', () => {
    expect(periodSpan(period())).toBe('Oct 2025 – Sep 2026');
    expect(periodSpan(period({ startMonth: '2026-09', endMonth: '2026-09', monthCount: 1 }))).toBe('Sep 2026');
    expect(periodSpan(period({ startMonth: null, endMonth: null }))).toBeNull();
  });

  it('reads a month in the local calendar, never a month early', () => {
    expect(monthYear('2026-01')).toBe('Jan 2026');
    expect(monthYear('2026-12')).toBe('Dec 2026');
  });

  it('names the earlier period the way a sentence needs it', () => {
    expect(againstName(period({ monthCount: 6 }))).toBe('the previous 6 months');
    expect(againstName(period({ monthCount: 1 }))).toBe('the month before');
    expect(againstName(period({ compare: 'SAME_PERIOD_LAST_YEAR' }))).toBe('the same months a year earlier');
  });

  it('says how much of the asked-for period the history covers, and is quiet when it covers it all', () => {
    expect(coverageNote(period())).toBeNull();
    expect(coverageNote(period({ monthCount: 5 }))).toBe('Your history covers 5 of the last 12 months.');
    expect(coverageNote(period({ range: 'THIS_YEAR', monthCount: 4, requestedMonths: 9 }))).toBe('Your history covers 4 of the 9 months asked for.');
    expect(coverageNote(period({ hasData: false, monthCount: 0 }))).toBeNull();
  });

  it('says why the newest month is left out', () => {
    expect(leftOutNote(period())).toBeNull();
    expect(leftOutNote(period({ leftOutMonth: '2026-10', leftOutReason: 'PARTIAL_MONTH' }))).toContain('Oct 2026 is left out');
    expect(leftOutNote(period({ leftOutMonth: '2026-10', leftOutReason: 'STATEMENT_BEHIND' }))).toContain('latest statement');
  });

  it('names months that are only part of a month, singular and plural', () => {
    expect(incompleteNote(period())).toBeNull();
    expect(incompleteNote(period({ incompleteMonths: ['2026-02'] }))).toMatch(/^Feb 2026 is only part of a month/);
    expect(incompleteNote(period({ incompleteMonths: ['2026-02', '2026-03'] }))).toMatch(/^Feb 2026, Mar 2026 are only part/);
  });
});

describe('comparison wording', () => {
  it('explains every reason a comparison is withheld, and says nothing for the ones that need no words', () => {
    const p = period();
    expect(gateNote(cmp({ gateReason: 'PRIOR_PERIOD_BEFORE_HISTORY' }), p)).toMatch(/does not reach back/);
    expect(gateNote(cmp({ gateReason: 'TOO_FEW_PRIOR_TRANSACTIONS' }), p)).toMatch(/too few/);
    expect(gateNote(cmp({ gateReason: 'COVERAGE_GAP' }), p)).toMatch(/statement is missing/);
    expect(gateNote(cmp({ gateReason: 'INCOMPLETE_MONTHS' }), p)).toMatch(/only part of a month/);
    expect(gateNote(cmp({ gateReason: 'SOMETHING_NEW' }), p)).toMatch(/cannot be compared/);
    expect(gateNote(cmp({ gateReason: 'ALL_TIME' }), p)).toBeNull();
    expect(gateNote(cmp({ gateReason: 'NO_TRANSACTION_HISTORY' }), p)).toBeNull();
    expect(gateNote(cmp({ reliable: true }), p)).toBeNull();
  });

  it('writes a change so direction never rests on colour', () => {
    expect(percentChange(12.4)).toBe('↑ 12%');
    expect(percentChange(-3.2)).toBe('↓ 3%');
    expect(percentChange(0.2)).toBe('No change');
    expect(percentChange(null)).toBeNull();
    expect(percentChange(Number.NaN)).toBeNull();
    expect(pointsChange(6.4)).toBe('↑ 6 pp');
    expect(pointsChange(-2)).toBe('↓ 2 pp');
    expect(pointsChange(0.1)).toBe('No change');
    expect(pointsChange(null)).toBeNull();
    expect(signedAmount(2000, fmt)).toBe('+₹2,000');
    expect(signedAmount(-1200, fmt)).toBe('-₹1,200');
    expect(signedAmount(0.4, fmt)).toBe('₹0');
  });
});

describe('copy for an empty page', () => {
  it('has a distinct explanation for each reason there is nothing to draw', () => {
    expect(noDataCopy(period({ hasData: false, noDataReason: 'NO_TRANSACTIONS' })).title).toBe('No transactions yet');
    expect(noDataCopy(period({ hasData: false, noDataReason: 'ONLY_PARTIAL_MONTH' })).title).toMatch(/still in progress/);
    expect(noDataCopy(period({ hasData: false, noDataReason: 'NO_MONTHS_IN_PERIOD' })).title).toBe('No data in this period');
  });
});

describe('axis and links', () => {
  it('shortens an axis amount in lakhs and crores', () => {
    expect(compactAmount(0)).toBe('₹0');
    expect(compactAmount(850)).toBe('₹850');
    expect(compactAmount(25000)).toBe('₹25K');
    expect(compactAmount(150000)).toBe('₹1.5L');
    expect(compactAmount(20000000)).toBe('₹2Cr');
    expect(compactAmount(-50000)).toBe('-₹50K');
  });

  it('builds the Transactions-tab filter from a drill, with only the filters it names', () => {
    expect(drillFilter({ from: '2026-07-01', to: '2026-09-30', categoryName: null, keyword: null }, 'Jul - Sep 2026'))
      .toEqual({ dateFrom: '2026-07-01', dateTo: '2026-09-30', label: 'Jul - Sep 2026' });
    expect(drillFilter({ from: '2026-07-01', to: '2026-09-30', categoryName: 'Food & Dining', keyword: null }, 'Food & Dining'))
      .toEqual({ dateFrom: '2026-07-01', dateTo: '2026-09-30', categoryName: 'Food & Dining', label: 'Food & Dining' });
    expect(drillFilter({ from: '2026-07-01', to: '2026-09-30', categoryName: null, keyword: 'Amazon' }, 'Amazon'))
      .toEqual({ dateFrom: '2026-07-01', dateTo: '2026-09-30', keyword: 'Amazon', label: 'Amazon' });
  });

  it('never filters by "Uncategorized", which is no category a filter can match', () => {
    expect(drillFilter({ from: '2026-07-01', to: '2026-09-30', categoryName: 'Uncategorized', keyword: null }, 'Uncategorized'))
      .toEqual({ dateFrom: '2026-07-01', dateTo: '2026-09-30', label: 'Uncategorized' });
  });

  it('finds the first and last day of a month, including February', () => {
    expect(monthBounds('2026-02')).toEqual({ from: '2026-02-01', to: '2026-02-28' });
    expect(monthBounds('2028-02')).toEqual({ from: '2028-02-01', to: '2028-02-29' });
    expect(monthBounds('2026-12')).toEqual({ from: '2026-12-01', to: '2026-12-31' });
  });
});
