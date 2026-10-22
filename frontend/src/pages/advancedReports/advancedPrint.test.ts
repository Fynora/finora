import { describe, expect, it } from 'vitest';
import { advancedPdfName, advancedPrintBody, advancedPrintDocument } from './advancedPrint';
import { fixtureReport } from './testFixtures';

describe('advancedPrintBody', () => {
  it('names the period and what it is compared with', () => {
    const html = advancedPrintBody(fixtureReport());
    expect(html).toContain('Apr 2026 – Sep 2026');
    expect(html).toContain('Compared with the previous 6 months');
  });

  it('shows each total with how it moved, in words that carry the direction', () => {
    const html = advancedPrintBody(fixtureReport());
    expect(html).toContain('₹6,00,000');
    expect(html).toContain('₹2,40,000');
    expect(html).toContain('↑ 11% vs the previous 6 months');
    expect(html).toContain('↑ 20% vs the previous 6 months');
    expect(html).toContain('↑ ₹20,000 vs the previous 6 months');
    expect(html).toContain('↓ 3 pp vs the previous 6 months');
    expect(html).toContain('60%');
  });

  it('gives the reason instead of change lines when the comparison was withheld', () => {
    const base = fixtureReport();
    const html = advancedPrintBody(fixtureReport({ comparison: { ...base.comparison, reliable: false, gateReason: 'COVERAGE_GAP' } }));
    expect(html).toContain('A statement is missing in this period or the one it is compared with, so they are not compared.');
    expect(html).not.toContain('vs the previous 6 months');
    expect(html).not.toContain('Compared with');
  });

  it('carries every caveat the page shows', () => {
    const base = fixtureReport();
    const html = advancedPrintBody(fixtureReport({
      period: { ...base.period, leftOutMonth: '2026-10', leftOutReason: 'PARTIAL_MONTH', incompleteMonths: ['2026-06'] },
      totals: { ...base.totals, savingsRatePct: null, savingsRateGateReason: 'NO_INCOME', unresolvedInflow: 5000, unresolvedInflowCount: 2 },
      months: base.months.map((m) => (m.month === '2026-06' ? { ...m, complete: false } : m)),
    }));
    expect(html).toContain('Oct 2026 is left out: it is still in progress');
    expect(html).toContain('Jun 2026 is only part of a month');
    expect(html).toContain('Savings rate. Not shown: no income was counted in this period.');
    expect(html).toContain('₹5,000 received is not counted as income yet.');
    expect(html).toContain('Investment transfers are not counted in any figure.');
    // The part month is marked in the month table too, not only in a note above it.
    expect(html).toMatch(/Jun 2026<span class="tag">Part month<\/span>/);
    // A withheld rate prints no change line under its dash.
    expect(html).not.toContain('pp vs');
  });

  it('escapes names the user controls', () => {
    const base = fixtureReport();
    const html = advancedPrintBody(fixtureReport({
      categories: { ...base.categories, rows: [{ ...base.categories.rows[0], name: 'Rent & Bills <shared>' }] },
      merchants: { ...base.merchants, rows: [{ ...base.merchants.rows[0], name: 'Shop "One"' }] },
      insights: [{ ...base.insights[0], headline: '<script>x</script>' }],
    }));
    expect(html).toContain('Rent &amp; Bills &lt;shared&gt;');
    expect(html).toContain('Shop &quot;One&quot;');
    expect(html).toContain('&lt;script&gt;x&lt;/script&gt;');
    expect(html).not.toContain('<shared>');
    expect(html).not.toContain('<script>');
  });

  it('lists categories with their change, and the rest as one Other row', () => {
    const html = advancedPrintBody(fixtureReport());
    expect(html).toContain('Food &amp; Dining');
    expect(html).toContain('+₹20,000');
    expect(html).toContain('-₹10,000');
    expect(html).toContain('Other (3 more)');
  });

  it('leaves out a section with nothing in it, rather than printing an empty heading', () => {
    const base = fixtureReport();
    const html = advancedPrintBody(fixtureReport({
      merchants: { ...base.merchants, rows: [] },
      income: { ...base.income, sources: [] },
      insights: [],
    }));
    expect(html).not.toContain('Merchants');
    expect(html).not.toContain('Income sources');
    expect(html).not.toContain('Key insights');
    expect(html).not.toContain('Larger than usual');
    expect(html).not.toContain('International spend');
  });

  it('prints unusual spends and international spend when there are some', () => {
    const html = advancedPrintBody(fixtureReport({
      unusualSpends: [{
        transactionId: 't1', date: '2026-07-14', label: 'Flight', category: 'Travel', amount: 12000,
        typicalAmount: 3000, timesTypical: 4, sampleSize: 6,
      }],
      international: {
        totalSpend: 9000, transactionCount: 3, purchasesSpend: 8000, otherChargesSpend: 1000,
        byCurrency: [{ currency: 'USD', foreignTotal: 95, rupeeTotal: 8000, transactionCount: 2 }],
      },
    }));
    expect(html).toContain('Larger than usual for the category');
    expect(html).toContain('14 Jul 2026');
    // September is "Sep", as everywhere else in the app -- not Intl's en-GB/en-IN "Sept".
    const sept = advancedPrintBody(fixtureReport({
      unusualSpends: [{ transactionId: 't2', date: '2026-09-03', label: 'Flight', category: 'Travel', amount: 12000, typicalAmount: 3000, timesTypical: 4, sampleSize: 6 }],
    }));
    expect(sept).toContain('3 Sep 2026');
    expect(sept).not.toContain('Sept');
    expect(html).toContain('₹3,000');
    expect(html).toContain('International spend');
    expect(html).toContain('USD');
    expect(html).toContain('₹8,000');
  });

  it('prints the key insights in the words the server wrote', () => {
    const html = advancedPrintBody(fixtureReport());
    expect(html).toContain('Your spending is 20% higher');
    expect(html).toContain('You spent ₹40,000 more than in the previous 6 months');
  });
});

describe('advancedPrintDocument', () => {
  it('is a whole page, with its styles inline and nothing fetched', () => {
    const doc = advancedPrintDocument(fixtureReport());
    expect(doc.startsWith('<!DOCTYPE html>')).toBe(true);
    expect(doc).toContain('<meta charset="utf-8" />');
    expect(doc).toContain('<style>');
    expect(doc).toContain('Apr 2026 – Sep 2026');
    expect(doc).not.toMatch(/<link|<img|<script|src=/);
  });
});

describe('advancedPdfName', () => {
  it('names the file by the months it covers', () => {
    expect(advancedPdfName(fixtureReport())).toBe('fynora-advanced-report-2026-04-to-2026-09.pdf');
  });
});
