import { describe, expect, it } from 'vitest';
import type { ReportData } from '../../api/endpoints';
import { toCsv } from '../../lib/download';
import { reportCsvRows } from './reportExport';

const base: ReportData = {
  month: '2026-09',
  income: 100000,
  expense: 40000,
  categories: [{ category: 'Rent', amount: 25000 }, { category: 'Food', amount: 15000 }],
};

const flat = (r: ReportData) => reportCsvRows(r).map((row) => row.join('|'));

describe('reportCsvRows', () => {
  it('writes the totals and the categories, and nothing else, for a report with no extras', () => {
    expect(flat(base)).toEqual([
      'Month|2026-09', 'Income|100000', 'Expense|40000', 'Net|60000',
      '', 'Category|Amount', 'Rent|25000', 'Food|15000',
    ]);
  });

  it('writes the savings rate to one decimal', () => {
    expect(flat({ ...base, savingsRatePct: 60.04 })).toContain('Savings rate (%)|60');
    expect(flat({ ...base, savingsRatePct: 33.349 })).toContain('Savings rate (%)|33.3');
  });

  it('says why the rate is withheld rather than leaving it out silently', () => {
    expect(flat({ ...base, savingsRatePct: null, savingsRateGateReason: 'UNRESOLVED_EXCEEDS_INCOME' }))
      .toContain('Savings rate|Not shown: money received has not been sorted into income yet');
    expect(flat({ ...base, income: 0, savingsRatePct: null, savingsRateGateReason: 'NO_INCOME' }))
      .toContain('Savings rate|Not shown: no income was counted this month');
    // An unknown reason from a newer server, or none at all: no row, never a made-up sentence.
    expect(flat({ ...base, savingsRatePct: null, savingsRateGateReason: 'SOMETHING_NEW' }).some((l) => l.startsWith('Savings rate'))).toBe(false);
  });

  it('writes the comparison against the month before only when the server trusted it', () => {
    const reliable = {
      previousMonth: '2026-08', reliable: true, gateReason: null, previousIncome: 90000, previousExpense: 50000,
      incomeDeltaPct: 11.11, expenseDeltaPct: -20, netDelta: 20000,
    };
    const rows = flat({ ...base, comparison: reliable });
    expect(rows).toContain('Income change vs August (%)|11.1');
    expect(rows).toContain('Expense change vs August (%)|-20');
    expect(rows).toContain('Net change vs August|20000');
  });

  it('says why there is no comparison when the server did not trust it, and writes no figures', () => {
    const rows = flat({
      ...base,
      comparison: {
        previousMonth: '2026-08', reliable: false, gateReason: 'PARTIAL_PRIOR_MONTH', previousIncome: null,
        previousExpense: null, incomeDeltaPct: null, expenseDeltaPct: null, netDelta: null,
      },
    });
    expect(rows).toContain('Comparison|August is only partly in your history, so it is not compared with September.');
    expect(rows.some((l) => l.startsWith('Income change'))).toBe(false);
  });

  it('carries the caveats a screen would show: money not counted as income, and a part-month', () => {
    const rows = flat({ ...base, unresolvedInflow: 84500, complete: false });
    expect(rows).toContain('Received, not counted as income yet|84500');
    expect(rows).toContain("Note|Part of this month isn't in yet, so these figures may change.");
  });

  it('writes neither caveat for a whole month with nothing unsorted', () => {
    const rows = flat({ ...base, unresolvedInflow: 0, complete: true });
    expect(rows.some((l) => l.startsWith('Received') || l.startsWith('Note'))).toBe(false);
  });

  it('writes the biggest spends and the spend per day as their own sections', () => {
    const rows = flat({
      ...base,
      biggestSpends: [{ id: 'a', date: '2026-09-14', label: 'Big Mart', category: 'Food', amount: 9000 }],
      dailySpend: [{ date: '2026-09-03', amount: 400 }, { date: '2026-09-14', amount: 9000 }],
    });
    expect(rows).toContain('Biggest spends');
    expect(rows).toContain('Date|Description|Category|Amount');
    expect(rows).toContain('2026-09-14|Big Mart|Food|9000');
    expect(rows).toContain('Spend by day');
    expect(rows).toContain('2026-09-03|400');
  });

  it('writes the spending by payment method, with the unidentified part named, when the screen would draw the card', () => {
    const rows = flat({
      ...base,
      paymentMethods: [{ method: 'UPI', amount: 6000 }, { method: 'CARD', amount: 3000 }, { method: 'UNIDENTIFIED', amount: 1000 }],
    });
    expect(rows).toContain('Spending by payment method');
    expect(rows).toContain('Method|Amount');
    expect(rows).toContain('UPI|6000');
    expect(rows).toContain('Card|3000');
    expect(rows).toContain('Not identified|1000');
  });

  it('writes no payment-method section when too little of the spending has a named method', () => {
    const rows = flat({ ...base, paymentMethods: [{ method: 'UPI', amount: 300 }, { method: 'UNIDENTIFIED', amount: 700 }] });
    expect(rows).not.toContain('Spending by payment method');
    expect(flat({ ...base })).not.toContain('Spending by payment method');
  });

  it('leaves out a section the report has no data for instead of writing an empty header', () => {
    const rows = flat({ ...base, biggestSpends: [], dailySpend: [] });
    expect(rows).not.toContain('Biggest spends');
    expect(rows).not.toContain('Spend by day');
  });

  it('is neutralised against formula injection through a purchase name, like a category name is', () => {
    const csv = toCsv(reportCsvRows({
      ...base,
      biggestSpends: [{ id: 'a', date: '2026-09-14', label: '=HYPERLINK("http://evil.example")', category: 'Food', amount: 100 }],
    }));
    expect(csv).toContain(`"'=HYPERLINK(""http://evil.example"")"`);
  });
});
