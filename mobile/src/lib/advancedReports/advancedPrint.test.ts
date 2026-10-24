import { ADVANCED_PRINT_CSS, advancedPdfName, advancedPrintBody, advancedPrintDocument } from './advancedPrint';
import { fixtureReport } from './testFixtures';

const NOW = new Date(2026, 9, 23);
const body = (over: Parameters<typeof fixtureReport>[0] = {}) => advancedPrintBody(fixtureReport(over), NOW);
const count = (html: string, needle: string) => html.split(needle).length - 1;

describe('advancedPrintBody: the cover', () => {
  it('names the period, how long it is and what it is compared with', () => {
    const html = body();
    expect(html).toContain('Apr 2026 – Sep 2026');
    expect(html).toContain('6 months, compared with the previous 6 months');
  });

  it('leads with what was kept, and the savings rate with how it moved', () => {
    const html = body();
    expect(html).toContain('You kept');
    expect(html).toContain('₹3,60,000');
    // Net is compared as an amount, as on the screen: a percent of a small or negative net misleads.
    expect(html).toContain('↑ ₹20,000 vs the previous 6 months');
    expect(html).toContain('60% of income kept');
    expect(html).toContain('↓ 3 pp vs the previous 6 months');
  });

  it('says plainly when more went out than came in', () => {
    const base = fixtureReport();
    const html = body({ totals: { ...base.totals, net: -50000, savingsRatePct: -10 } });
    expect(html).toContain('You spent more than came in by');
    expect(html).toContain('₹50,000');
    expect(html).toContain('Spending was 10% more than income');
    expect(html).not.toContain('You kept');
  });

  it('shows income, spending and the average month, each with how it moved', () => {
    const html = body();
    expect(html).toContain('₹6,00,000');
    expect(html).toContain('↑ 11% vs the previous 6 months');
    expect(html).toContain('₹2,40,000');
    expect(html).toContain('↑ 20% vs the previous 6 months');
    expect(html).toContain('₹40,000');
  });

  it('gives the reason instead of change lines when the comparison was withheld', () => {
    const base = fixtureReport();
    const html = body({ comparison: { ...base.comparison, reliable: false, gateReason: 'COVERAGE_GAP' } });
    expect(html).toContain('A statement is missing in this period or the one it is compared with, so they are not compared.');
    expect(html).not.toContain('vs the previous 6 months');
    expect(html).not.toContain('compared with the previous 6 months');
  });
});

describe('advancedPrintBody: what it qualifies', () => {
  it('carries every caveat the page shows', () => {
    const base = fixtureReport();
    const html = body({
      period: { ...base.period, leftOutMonth: '2026-10', leftOutReason: 'PARTIAL_MONTH', incompleteMonths: ['2026-06'] },
      totals: { ...base.totals, savingsRatePct: null, savingsRateGateReason: 'NO_INCOME', unresolvedInflow: 5000, unresolvedInflowCount: 2 },
      months: base.months.map((m) => (m.month === '2026-06' ? { ...m, complete: false } : m)),
    });
    expect(html).toContain('Oct 2026 is left out: it is still in progress');
    expect(html).toContain('Jun 2026 is only part of a month');
    expect(html).toContain('Savings rate. Not shown: no income was counted in this period.');
    expect(html).toContain('₹5,000 received is not counted as income yet.');
    expect(html).toContain('Investment transfers are not counted in any figure.');
    // The part month is marked in the month table and faded in the chart, not only noted above.
    expect(html).toMatch(/Jun 2026<span class="tag">Part month<\/span>/);
    expect(html).toContain('Faded bars are part months.');
    // A withheld rate prints no change line, and no rate.
    expect(html).not.toContain('pp vs');
    expect(html).not.toContain('of income kept');
  });

  it('escapes names the user controls', () => {
    const base = fixtureReport();
    const html = body({
      categories: { ...base.categories, rows: [{ ...base.categories.rows[0], name: 'Rent & Bills <shared>' }] },
      merchants: { ...base.merchants, rows: [{ ...base.merchants.rows[0], name: 'Shop "One"' }] },
      insights: [{ ...base.insights[0], headline: '<script>x</script>' }],
    });
    expect(html).toContain('Rent &amp; Bills &lt;shared&gt;');
    expect(html).toContain('Shop &quot;One&quot;');
    expect(html).toContain('&lt;script&gt;x&lt;/script&gt;');
    expect(html).not.toContain('<shared>');
    expect(html).not.toContain('<script>');
  });
});

describe('advancedPrintBody: who you paid most', () => {
  // A friend can rank among the merchants, and money sent to a person is not a purchase.
  it('heads the merchant count "Payments", never "Purchases"', () => {
    const html = body();
    expect(html).toContain('Payments</th>');
    expect(html).not.toMatch(/purchases<\/th>/i);
  });
});

describe('advancedPrintBody: the charts', () => {
  it('draws money in and out for every month of the period', () => {
    const html = body();
    expect(count(html, 'class="bar-in"')).toBe(6);
    expect(count(html, 'class="bar-out"')).toBe(6);
    expect(html).not.toContain('Faded bars are part months.');
  });

  it('draws nothing broken when a period has no money in it', () => {
    const base = fixtureReport();
    const html = body({
      months: base.months.map((m) => ({ ...m, income: 0, expense: 0, net: 0, savingsRatePct: null })),
      categories: { ...base.categories, rows: [], otherAmount: 0, otherSharePct: 0, categoryCount: 0 },
      paymentMethods: [],
    });
    expect(html).not.toContain('NaN');
    expect(html).not.toContain('Infinity');
  });

  it('draws the kept line across the cover from every month', () => {
    const html = body();
    expect(count(html, 'class="art"')).toBe(1);
    expect(html).not.toContain('NaN');
  });

  it('splits spending into one ring, an arc per category and one for the rest, with the total inside', () => {
    const html = body();
    const categories = html.slice(html.indexOf('Where the money went'), html.indexOf('Who you paid most'));
    expect(count(categories, 'class="arc"')).toBe(5);
    expect(categories).toContain('₹2,40,000');
    expect(html).toContain('Food &amp; Dining');
    expect(html).toContain('+₹20,000');
    expect(html).toContain('-₹10,000');
    expect(html).toContain('Other (3 more)');
  });
});

describe('advancedPrintBody: the sections', () => {
  it('leaves out a section with nothing in it, rather than printing an empty heading', () => {
    const base = fixtureReport();
    const html = body({ merchants: { ...base.merchants, rows: [] }, income: { ...base.income, sources: [] }, insights: [] });
    expect(html).not.toContain('Who you paid most');
    expect(html).not.toContain('Where income came from');
    expect(html).not.toContain('What stood out');
    expect(html).not.toContain('Larger than usual');
    expect(html).not.toContain('Spending abroad');
  });

  it('prints unusual spends and international spend when there are some', () => {
    const html = body({
      unusualSpends: [{
        transactionId: 't1', date: '2026-07-14', label: 'Flight', category: 'Travel', amount: 12000,
        typicalAmount: 3000, timesTypical: 4, sampleSize: 6,
      }],
      international: {
        totalSpend: 9000, transactionCount: 3, purchasesSpend: 8000, otherChargesSpend: 1000,
        byCurrency: [{ currency: 'USD', foreignTotal: 95, rupeeTotal: 8000, transactionCount: 2 }],
      },
    });
    expect(html).toContain('Larger than usual for the category');
    expect(html).toContain('14 Jul 2026');
    expect(html).toContain('₹3,000');
    expect(html).toContain('Spending abroad');
    expect(html).toContain('USD 95.00');
    expect(html).toContain('₹8,000');
    // September is "Sep", as everywhere else in the app -- not Intl's en-GB/en-IN "Sept".
    const sept = body({
      unusualSpends: [{ transactionId: 't2', date: '2026-09-03', label: 'Flight', category: 'Travel', amount: 12000, typicalAmount: 3000, timesTypical: 4, sampleSize: 6 }],
    });
    expect(sept).toContain('3 Sep 2026');
    expect(sept).not.toContain('Sept');
  });

  it('says how steady income was, in the Income tab\'s words', () => {
    expect(body()).toContain('Income came in during 6 of 6 months. Among whole months the highest was Sep 2026 (₹1,10,000) and the lowest Apr 2026 (₹95,000), 86% of the highest.');
  });

  it('leads with the key insights, in the words the server wrote', () => {
    const html = body();
    expect(html).toContain('What stood out');
    expect(html).toContain('Your spending is 20% higher');
    expect(html).toContain('You spent ₹40,000 more than in the previous 6 months');
    // Read first: before the month-by-month detail.
    expect(html.indexOf('What stood out')).toBeLessThan(html.indexOf('Money in and out, month by month'));
  });

  it('says when it was prepared, on the masthead and at the end', () => {
    const html = body();
    expect(count(html, '23 Oct 2026')).toBe(2);
    expect(html).toContain('Prepared by Fynora on 23 Oct 2026');
  });

  it('explains how its figures are made, by the rules the server applies', () => {
    const html = body();
    expect(html).toContain('About this report');
    expect(html).toContain('A payment is marked larger than usual when it is at least three times the typical payment in its category');
    expect(html).toContain('at least five other payments');
    expect(html).toContain('at least 5% of the period');
    expect(html).toContain('Transfers to your own investments are not counted');
  });

  it('names the report and numbers the pages in the page margin', () => {
    const html = body();
    expect(html).toMatch(/@bottom-left \{[^}]*content: "Fynora Advanced report, Apr 2026 – Sep 2026"/);
    expect(html).toMatch(/@bottom-right \{[^}]*counter\(page\)[^}]*counter\(pages\)/);
    // Claimed empty, so Chrome's default print header (date and time) does not sit above the masthead.
    expect(html).toContain('@top-left { content: ""; }');
  });

  it('keeps a name with a quote or backslash from breaking the page-margin text', () => {
    // The period span is all the margin text carries; it is built from months, but escape it regardless.
    const html = body();
    expect(html).not.toMatch(/content: "[^"]*\\(?!["\\])/);
  });
});

describe('ADVANCED_PRINT_CSS', () => {
  // On web the sheet sits inside the app, whose Tailwind reset makes headings plain text and drops list
  // bullets; browser defaults cannot be leaned on, so the sheet sets them itself.
  it('sets heading weight and list markers itself rather than relying on browser defaults', () => {
    expect(ADVANCED_PRINT_CSS).toMatch(/\.fy-ar h1 \{[^}]*font-weight: \d00/);
    expect(ADVANCED_PRINT_CSS).toMatch(/\.fy-ar h2 \{[^}]*font-weight: \d00/);
    expect(ADVANCED_PRINT_CSS).toMatch(/\.fy-ar ul \{[^}]*list-style: disc/);
  });

  // Browsers and iOS drop background colours when printing unless told otherwise, which would leave the
  // cover's light text on white. Every coloured fill is drawn as SVG instead.
  it('paints no coloured background in CSS', () => {
    expect(ADVANCED_PRINT_CSS).not.toMatch(/background(?:-color)?:(?!\s*(?:#fff\b|transparent|none))/i);
    expect(ADVANCED_PRINT_CSS).toMatch(/background: #fff/);
  });
});

describe('advancedPrintDocument', () => {
  it('is a whole page, with its styles inline and nothing fetched', () => {
    const doc = advancedPrintDocument(fixtureReport(), NOW);
    expect(doc.startsWith('<!DOCTYPE html>')).toBe(true);
    expect(doc).toContain('<meta charset="utf-8" />');
    expect(doc).toContain('<style>');
    expect(doc).toContain('Apr 2026 – Sep 2026');
    expect(doc).not.toMatch(/<link|<img|<script|src=|href=/);
  });
});

describe('advancedPdfName', () => {
  it('names the file by the months it covers', () => {
    expect(advancedPdfName(fixtureReport())).toBe('fynora-advanced-report-2026-04-to-2026-09.pdf');
  });
});
