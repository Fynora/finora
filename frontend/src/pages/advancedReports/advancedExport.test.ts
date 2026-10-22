import { describe, expect, it } from 'vitest';
import { advancedCsvRows, advancedExportName } from './advancedExport';
import { fixtureMonth, fixtureReport } from './testFixtures';

const cell = (rows: (string | number)[][], label: string) => rows.find((r) => r[0] === label);

describe('advancedCsvRows', () => {
  it('writes the period, the totals and how they moved against the earlier period', () => {
    const rows = advancedCsvRows(fixtureReport());

    expect(cell(rows, 'Period')).toEqual(['Period', 'Apr 2026 – Sep 2026']);
    expect(cell(rows, 'Months in the period')).toEqual(['Months in the period', 6]);
    expect(cell(rows, 'Income')).toEqual(['Income', 600000]);
    expect(cell(rows, 'Expense')).toEqual(['Expense', 240000]);
    expect(cell(rows, 'Net')).toEqual(['Net', 360000]);
    expect(cell(rows, 'Average monthly expense')).toEqual(['Average monthly expense', 40000]);
    expect(cell(rows, 'Savings rate (%)')).toEqual(['Savings rate (%)', 60]);
    expect(cell(rows, 'Income change vs the previous 6 months (%)')).toEqual(['Income change vs the previous 6 months (%)', 11.1]);
    expect(cell(rows, 'Expense change vs the previous 6 months (%)')).toEqual(['Expense change vs the previous 6 months (%)', 20]);
    expect(cell(rows, 'Net change vs the previous 6 months')).toEqual(['Net change vs the previous 6 months', 20000]);
    expect(cell(rows, 'Savings rate change vs the previous 6 months (points)')).toEqual(['Savings rate change vs the previous 6 months (points)', -3]);
  });

  it('always says investments are not counted, since a file has no screen to say it', () => {
    expect(advancedCsvRows(fixtureReport())).toContainEqual(['Note', 'Investment transfers are not counted in any figure.']);
  });

  it('says why there is no comparison instead of writing empty change rows', () => {
    const base = fixtureReport();
    const rows = advancedCsvRows(fixtureReport({
      comparison: { ...base.comparison, reliable: false, gateReason: 'COVERAGE_GAP', incomeDeltaPct: null, expenseDeltaPct: null, netDelta: null },
    }));
    expect(cell(rows, 'Comparison')?.[1]).toMatch(/statement is missing/);
    expect(rows.some((r) => String(r[0]).includes('change vs'))).toBe(false);
  });

  it('says why the savings rate is not there', () => {
    const base = fixtureReport();
    const rows = advancedCsvRows(fixtureReport({ totals: { ...base.totals, savingsRatePct: null, savingsRateGateReason: 'NO_INCOME' } }));
    expect(cell(rows, 'Savings rate')?.[1]).toMatch(/no income was counted/);
    expect(cell(rows, 'Savings rate (%)')).toBeUndefined();
  });

  it('carries every caveat the page shows: a month left out, months only partly in, credits not counted as income', () => {
    const base = fixtureReport();
    const rows = advancedCsvRows(fixtureReport({
      period: { ...base.period, leftOutMonth: '2026-10', leftOutReason: 'PARTIAL_MONTH', incompleteMonths: ['2026-05'] },
      totals: { ...base.totals, unresolvedInflow: 12000, unresolvedInflowCount: 2 },
    }));
    const notes = rows.filter((r) => r[0] === 'Note').map((r) => String(r[1]));
    expect(notes.some((n) => n.startsWith('Oct 2026 is left out'))).toBe(true);
    expect(notes.some((n) => n.startsWith('May 2026 is only part of a month'))).toBe(true);
    expect(cell(rows, 'Received, not counted as income yet')).toEqual(['Received, not counted as income yet', 12000]);
  });

  it('writes one row per month, with whether it was a whole month', () => {
    const base = fixtureReport();
    const rows = advancedCsvRows(fixtureReport({
      months: [fixtureMonth('2026-04'), fixtureMonth('2026-05', { complete: false, savingsRatePct: null })],
      period: { ...base.period, monthCount: 2 },
    }));
    const i = rows.findIndex((r) => r[0] === 'Month');
    expect(rows[i]).toEqual(['Month', 'Income', 'Expense', 'Net', 'Savings rate (%)', 'Whole month']);
    expect(rows[i + 1]).toEqual(['2026-04', 100000, 40000, 60000, 60, 'Yes']);
    expect(rows[i + 2]).toEqual(['2026-05', 100000, 40000, 60000, '', 'No']);
  });

  it('lists categories with their earlier amounts and leaves the earlier columns empty when withheld', () => {
    const base = fixtureReport();
    const withheld = advancedCsvRows(fixtureReport({
      categories: { ...base.categories, rows: base.categories.rows.map((c) => ({ ...c, previousAmount: null, changeAmount: null, changePct: null })) },
    }));
    expect(cell(withheld, 'Food & Dining')).toEqual(['Food & Dining', 60000, 25, '', '']);
    expect(advancedCsvRows(base)).toContainEqual(['Food & Dining', 60000, 25, 40000, 20000]);
    expect(advancedCsvRows(base)).toContainEqual(['Other (3 more)', 18000, 7.5, '', '']);
  });

  it('leaves out a section the report has nothing for, rather than writing an empty header', () => {
    const base = fixtureReport();
    const rows = advancedCsvRows(fixtureReport({
      unusualSpends: [], insights: [], paymentMethods: [{ method: 'UNIDENTIFIED', amount: 5000 }],
      merchants: { ...base.merchants, rows: [] },
    }));
    expect(cell(rows, 'Larger than usual for the category')).toBeUndefined();
    expect(cell(rows, 'Key insights')).toBeUndefined();
    expect(cell(rows, 'Merchant')).toBeUndefined();
    // Spending that is all "not identified" is too little to say anything about, as on the page.
    expect(cell(rows, 'Spending by payment method')).toBeUndefined();
  });

  it('writes the unusual purchases and the insights when there are some', () => {
    const base = fixtureReport();
    const rows = advancedCsvRows(fixtureReport({
      unusualSpends: [{ transactionId: 't1', date: '2026-08-17', label: 'IndiGo', category: 'Travel', amount: 38400, typicalAmount: 4000, timesTypical: 9.6, sampleSize: 6 }],
    }));
    expect(rows).toContainEqual(['2026-08-17', 'IndiGo', 'Travel', 38400, 4000]);
    expect(rows).toContainEqual([base.insights[0].headline, base.insights[0].detail]);
  });

  it('names the file for the months it covers', () => {
    expect(advancedExportName(fixtureReport())).toBe('fynora-advanced-report-2026-04-to-2026-09.csv');
  });
});
