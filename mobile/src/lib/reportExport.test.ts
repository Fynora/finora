import { File, Paths } from 'expo-file-system';
import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import { shareCsv, sharePdf, toCsv, toPrintableHtml } from './reportExport';
import type { ReportData } from '../api/endpoints';

jest.mock('expo-print', () => ({ printToFileAsync: jest.fn() }));
jest.mock('expo-sharing', () => ({
  isAvailableAsync: jest.fn().mockResolvedValue(true),
  shareAsync: jest.fn().mockResolvedValue(undefined),
}));

const report: ReportData = {
  month: '2026-07',
  income: 82000,
  expense: 51500,
  categories: [
    { category: 'Groceries', amount: 12000 },
    { category: 'Dining "out"', amount: 8400 },
    { category: 'Rent & Bills <shared>', amount: 31100 },
  ],
};

describe('toCsv', () => {
  it('includes the totals and every category row', () => {
    const lines = toCsv(report).split('\n');
    expect(lines[0]).toBe('"Month","2026-07"');
    expect(lines).toContain('"Income","82000"');
    expect(lines).toContain('"Net","30500"');
    expect(lines).toContain('"Groceries","12000"');
    expect(lines).toHaveLength(9); // 4 totals + blank + header + 3 categories
  });

  // Without quote doubling this row ends the quoted field early and every column after it shifts,
  // which no error surfaces -- the file just opens wrong in a spreadsheet.
  it('doubles quotes inside a value (RFC 4180)', () => {
    expect(toCsv(report)).toContain('"Dining ""out""","8400"');
  });

  it('keeps commas inside a value from splitting the row', () => {
    const csv = toCsv({ ...report, categories: [{ category: 'Food, drink', amount: 100 }] });
    const row = csv.split('\n').find((l) => l.startsWith('"Food'));
    expect(row).toBe('"Food, drink","100"');
  });

  it('handles a month with no expenses', () => {
    const csv = toCsv({ month: '2026-07', income: 0, expense: 0, categories: [] });
    expect(csv).toContain('"Category","Amount"');
    expect(csv).not.toContain('undefined');
  });

  // Category names are user-controlled and flow into a file a spreadsheet may later open. Without
  // this guard, a category named e.g. `=HYPERLINK(...)` would execute as a formula on open instead
  // of displaying as text.
  it('neutralises a category name that starts with a formula-triggering character', () => {
    const csv = toCsv({
      ...report,
      categories: [{ category: '=HYPERLINK("http://evil.example","click")', amount: 100 }],
    });
    const row = csv.split('\n').find((l) => l.includes('HYPERLINK'));
    expect(row).toBe('"\'=HYPERLINK(""http://evil.example"",""click"")","100"');
  });

  // A leading space before the formula-triggering character must not slip past the guard --
  // spreadsheet apps can still evaluate a leading-space-then-formula value as a formula on import.
  it('neutralises a category name with leading whitespace before a formula-triggering character', () => {
    const csv = toCsv({
      ...report,
      categories: [{ category: " =cmd|'/c calc'!A0", amount: 100 }],
    });
    const row = csv.split('\n').find((l) => l.includes('cmd'));
    expect(row).toBe(`"' =cmd|'/c calc'!A0","100"`);
  });

  // A lone leading tab or carriage return is itself a formula trigger (not just whitespace to
  // skip past) -- stripping it before the regex check, e.g. via a blanket trimStart(), would
  // silently defeat the guard for this input shape.
  it('neutralises a category name starting with a tab', () => {
    const csv = toCsv({ ...report, categories: [{ category: '\tDining', amount: 100 }] });
    const row = csv.split('\n').find((l) => l.includes('Dining') && l.includes('100'));
    expect(row).toBe(`"'\tDining","100"`);
  });

  it('does not mangle a genuine negative amount', () => {
    const csv = toCsv({ ...report, categories: [{ category: 'Refund', amount: -500 }] });
    expect(csv).toContain('"Refund","-500"');
  });
});

describe('toCsv — what qualifies the figures', () => {
  const plain: ReportData = { month: '2026-09', income: 100000, expense: 40000, categories: [{ category: 'Rent', amount: 25000 }] };
  const rows = (r: ReportData) => toCsv(r).split('\n').map((l) => l.replace(/"/g, '').split(',').join('|'));
  const unreliable = (gateReason: string) => ({
    previousMonth: '2026-08', reliable: false, gateReason, previousIncome: null, previousExpense: null,
    incomeDeltaPct: null, expenseDeltaPct: null, netDelta: null,
  });

  it('writes nothing extra for a report with no extras', () => {
    expect(rows(plain)).toEqual(['Month|2026-09', 'Income|100000', 'Expense|40000', 'Net|60000', '', 'Category|Amount', 'Rent|25000']);
  });

  it('writes the savings rate to one decimal, or says why it is withheld', () => {
    expect(rows({ ...plain, savingsRatePct: 33.349 })).toContain('Savings rate (%)|33.3');
    expect(toCsv({ ...plain, savingsRatePct: null, savingsRateGateReason: 'UNRESOLVED_EXCEEDS_INCOME' }))
      .toContain('"Savings rate","Not shown: money received has not been sorted into income yet"');
    expect(toCsv({ ...plain, savingsRatePct: null, savingsRateGateReason: 'SOMETHING_NEW' })).not.toContain('Savings rate');
  });

  it('writes the comparison only when the server trusted it, and the reason when it did not', () => {
    const reliable = {
      previousMonth: '2026-08', reliable: true, gateReason: null, previousIncome: 90000, previousExpense: 50000,
      incomeDeltaPct: 11.11, expenseDeltaPct: -20, netDelta: 20000,
    };
    const ok = rows({ ...plain, comparison: reliable });
    expect(ok).toContain('Income vs August (%)|11.1');
    expect(ok).toContain('Expense vs August (%)|-20');
    expect(ok).toContain('Net vs August|20000');

    const csv = toCsv({ ...plain, comparison: unreliable('PARTIAL_PRIOR_MONTH') });
    expect(csv).toContain('"Comparison","August is only partly in your history, so it is not compared with September."');
    expect(csv).not.toContain('Income vs');
  });

  it('carries the caveats a screen would show: money not counted as income, and a part-month', () => {
    const csv = toCsv({ ...plain, unresolvedInflow: 84500, complete: false });
    expect(csv).toContain('"Received, not counted as income yet","84500"');
    expect(csv).toContain(`"Note","Part of this month isn't in yet, so these figures may change."`);
    expect(toCsv({ ...plain, unresolvedInflow: 0, complete: true })).not.toContain('Note');
  });

  it('writes the biggest spends and the spend per day, and leaves out an empty section', () => {
    const csv = toCsv({
      ...plain,
      biggestSpends: [{ id: 'a', date: '2026-09-14', label: 'Big Mart', category: 'Food', amount: 9000 }],
      dailySpend: [{ date: '2026-09-14', amount: 9000 }],
    });
    expect(csv).toContain('"Biggest spends"');
    expect(csv).toContain('"2026-09-14","Big Mart","Food","9000"');
    expect(csv).toContain('"Spend by day"');
    expect(toCsv({ ...plain, biggestSpends: [], dailySpend: [] })).not.toContain('Biggest spends');
  });

  it('neutralises a purchase name that starts with a formula-triggering character', () => {
    const csv = toCsv({
      ...plain,
      biggestSpends: [{ id: 'a', date: '2026-09-14', label: '=HYPERLINK("http://evil.example")', category: 'Food', amount: 100 }],
    });
    expect(csv).toContain(`"'=HYPERLINK(""http://evil.example"")"`);
  });
});

describe('toPrintableHtml — what qualifies the figures', () => {
  const plain: ReportData = { month: '2026-09', income: 100000, expense: 40000, categories: [{ category: 'Rent', amount: 25000 }] };

  it('prints no notes list for a report with nothing to qualify', () => {
    expect(toPrintableHtml(plain)).not.toContain('class="notes"');
  });

  it('prints the caveats, so the page never reads more certain than the app', () => {
    const html = toPrintableHtml({
      ...plain, savingsRatePct: 60, unresolvedInflow: 84500, complete: false,
      comparison: {
        previousMonth: '2026-08', reliable: true, gateReason: null, previousIncome: 90000, previousExpense: 50000,
        incomeDeltaPct: 11.11, expenseDeltaPct: -20, netDelta: 20000,
      },
    });
    expect(html).toContain('Savings rate: 60% of income.');
    expect(html).toContain('Income ↑ 11% vs August');
    expect(html).toContain('Expenses ↓ 20% vs August');
    expect(html).toContain("₹84,500 received isn't counted as income yet.");
    expect(html).toContain("Part of this month isn't in yet, so these figures may change.");
  });

  it('prints the reason when the savings rate or the comparison is not shown', () => {
    const html = toPrintableHtml({
      ...plain, savingsRatePct: null, savingsRateGateReason: 'NO_INCOME',
      comparison: {
        previousMonth: '2026-08', reliable: false, gateReason: 'COVERAGE_GAP', previousIncome: null,
        previousExpense: null, incomeDeltaPct: null, expenseDeltaPct: null, netDelta: null,
      },
    });
    expect(html).toContain('Not shown: no income was counted this month');
    expect(html).toContain('A statement is missing around September or August, so they are not compared.');
  });

  it('prints the biggest spends and the spend per day, escaped, and omits them when there are none', () => {
    const html = toPrintableHtml({
      ...plain,
      biggestSpends: [{ id: 'a', date: '2026-09-14', label: 'Tom & <Jerry>', category: 'Food', amount: 9000 }],
      dailySpend: [{ date: '2026-09-14', amount: 9000 }],
    });
    expect(html).toContain('Biggest spends');
    expect(html).toContain('Tom &amp; &lt;Jerry&gt;');
    expect(html).not.toContain('<Jerry>');
    expect(html).toContain('Spend by day');
    const bare = toPrintableHtml(plain);
    expect(bare).not.toContain('Biggest spends');
    expect(bare).not.toContain('Spend by day');
  });
});

describe('toPrintableHtml', () => {
  it('renders the totals and category rows', () => {
    const html = toPrintableHtml(report);
    expect(html).toContain('2026-07');
    expect(html).toContain('Groceries');
    expect(html).toContain('Category Breakdown');
  });

  // A category containing < or & would otherwise produce malformed markup that renders as a
  // truncated or mangled row rather than failing loudly.
  it('escapes HTML-significant characters in category names', () => {
    const html = toPrintableHtml(report);
    expect(html).toContain('Rent &amp; Bills &lt;shared&gt;');
    expect(html).not.toContain('<shared>');
    expect(html).toContain('Dining &quot;out&quot;');
  });

  it('says so plainly when there are no expenses, rather than printing an empty table', () => {
    const html = toPrintableHtml({ month: '2026-07', income: 5000, expense: 0, categories: [] });
    expect(html).toContain('No expenses recorded this month.');
    expect(html).not.toContain('<table>');
  });

  // useMarkupFormatter aside, expo-print's own docs warn that HTML without a doctype can produce a
  // blank trailing page.
  it('declares a doctype', () => {
    expect(toPrintableHtml(report).startsWith('<!DOCTYPE html>')).toBe(true);
  });
});

/**
 * D2 (Track D security cleanup). The exported file exists on disk only to hand the OS share
 * sheet a real URI -- once shareAsync settles, it has to go, whichever of the two builders wrote
 * it and whether the share itself succeeded or threw.
 */
describe('shareCsv / sharePdf clean up the cache file after sharing', () => {
  const shareAsync = Sharing.shareAsync as jest.Mock;
  const isAvailableAsync = Sharing.isAvailableAsync as jest.Mock;
  const printToFileAsync = Print.printToFileAsync as jest.Mock;

  beforeEach(() => {
    shareAsync.mockReset().mockResolvedValue(undefined);
    isAvailableAsync.mockReset().mockResolvedValue(true);
  });

  // Bug found in review (Track D/D2): the isAvailableAsync() check used to run before shareCsv's
  // own try/finally, so a device with sharing unavailable threw straight out of share() and left
  // the just-written cache file behind with no cleanup at all.
  it('shareCsv still deletes its cache file when sharing is unavailable on the device', async () => {
    isAvailableAsync.mockResolvedValue(false);

    await expect(shareCsv(report)).rejects.toThrow('Sharing is not available on this device.');

    const file = new File(Paths.cache, `fynora-report-${report.month}.csv`);
    expect(file.exists).toBe(false);
  });

  it('shareCsv deletes its own cache file after a successful share', async () => {
    await shareCsv(report);

    const file = new File(Paths.cache, `fynora-report-${report.month}.csv`);
    expect(file.exists).toBe(false);
  });

  it('shareCsv still deletes its cache file when the share itself throws', async () => {
    shareAsync.mockRejectedValue(new Error('share sheet dismissed with an error'));

    await expect(shareCsv(report)).rejects.toThrow();

    const file = new File(Paths.cache, `fynora-report-${report.month}.csv`);
    expect(file.exists).toBe(false);
  });

  it('sharePdf deletes the file expo-print wrote after a successful share', async () => {
    const printed = new File(Paths.cache, 'printed-report.pdf');
    printed.create();
    printed.write('%PDF-fake');
    printToFileAsync.mockResolvedValue({ uri: printed.uri });

    await sharePdf(report);

    expect(printed.exists).toBe(false);
  });

  it('sharePdf still deletes the printed file when the share itself throws', async () => {
    const printed = new File(Paths.cache, 'printed-report-2.pdf');
    printed.create();
    printed.write('%PDF-fake');
    printToFileAsync.mockResolvedValue({ uri: printed.uri });
    shareAsync.mockRejectedValue(new Error('share sheet dismissed with an error'));

    await expect(sharePdf(report)).rejects.toThrow();

    expect(printed.exists).toBe(false);
  });
});
