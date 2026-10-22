import { File, Paths } from 'expo-file-system';
import * as Print from 'expo-print';
import type { ReportData } from '../api/endpoints';
import { fmtCurrency, monthLabelLong } from './format';
import { comparisonGateNote, comparisonMonthName, deltaPhrase, netChangePhrase } from './reportSections/comparison';
import { methodBreakdown } from './reportSections/methodBreakdown';
import { shareFileAndCleanUp } from './shareFile';

/**
 * Turning a month's report into something the user can keep.
 *
 * The web page does this with a Blob + a synthetic `<a download>` click for CSV and
 * `window.print()` for PDF. Neither exists on native, and a file written into the app's sandbox is
 * invisible to the user anyway -- so both routes end at the system share sheet, which is where
 * "save to Files", "mail it to myself" and AirPrint/Android print actually live. Same reasoning,
 * and the same cache-directory choice, as statementImportsApi.downloadFile.
 *
 * The two builders are pure and separately tested: escaping is the part that breaks silently. A
 * category named `Dining "out"` produces a corrupt CSV column without proper quote doubling, and
 * one named `Rent & Bills <shared>` produces malformed HTML without escaping -- neither throws,
 * both just quietly render wrong.
 *
 * csvCell also has to defend against formula interpretation, not just CSV parsing: category names
 * are user-controlled (CategorizationService.resolveOrCreateCategory takes any string from manual
 * entry, bulk recategorize, or import review), and Excel/Sheets/LibreOffice evaluate a cell value
 * starting with `=`, `+`, `-`, `@`, or a leading tab/carriage return as a formula on open, quoted
 * or not. Mirrors frontend/src/lib/download.ts's csvCell.
 */

export function csvCell(value: string): string {
  const isPlainNumber = value.trim() !== '' && Number.isFinite(Number(value));
  const needsFormulaGuard = !isPlainNumber && /^[=+\-@\t\r]/.test(value.replace(/^ +/, ''));
  const guarded = needsFormulaGuard ? `'${value}` : value;
  // RFC 4180: wrap in quotes, and double any quote inside.
  return `"${guarded.replace(/"/g, '""')}"`;
}

/** One decimal, so 12.34 reads as 12.3 and a whole number stays whole. */
const oneDecimal = (n: number) => String(Math.round(n * 10) / 10);

/** Why the savings rate is not in the file, in the words the report itself uses. */
function withheldRate(reason: string | null | undefined): string | null {
  switch (reason) {
    case 'NO_INCOME': return 'Not shown: no income was counted this month';
    case 'UNRESOLVED_EXCEEDS_INCOME': return 'Not shown: money received has not been sorted into income yet';
    default: return null;
  }
}

const PART_MONTH_NOTE = "Part of this month isn't in yet, so these figures may change.";

/**
 * The month as CSV rows: the totals and what qualifies them, then the categories, the biggest spends
 * and the spend per day. A file has no screen around it to explain a figure, so every caveat the
 * report shows on screen is a row here too: a month only partly in the data, money received that is
 * not counted as income yet, a savings rate the server withheld, a comparison it did not trust. A
 * section the report has no data for (an older server, a month with nothing spent) is left out whole
 * rather than written as an empty header. Mirrors frontend/src/pages/reportSections/reportExport.ts.
 */
function reportRows(report: ReportData): string[][] {
  const rows: string[][] = [
    ['Month', report.month],
    ['Income', String(report.income)],
    ['Expense', String(report.expense)],
    ['Net', String(report.income - report.expense)],
  ];

  if (report.savingsRatePct != null) rows.push(['Savings rate (%)', oneDecimal(report.savingsRatePct)]);
  else {
    const why = withheldRate(report.savingsRateGateReason);
    if (why) rows.push(['Savings rate', why]);
  }

  const cmp = report.comparison;
  if (cmp) {
    const prior = comparisonMonthName(cmp.previousMonth, report.month);
    if (cmp.reliable) {
      // "change", because the figure is how far it moved, not the earlier month's income or net.
      if (cmp.incomeDeltaPct != null) rows.push([`Income change vs ${prior} (%)`, oneDecimal(cmp.incomeDeltaPct)]);
      if (cmp.expenseDeltaPct != null) rows.push([`Expense change vs ${prior} (%)`, oneDecimal(cmp.expenseDeltaPct)]);
      if (cmp.netDelta != null) rows.push([`Net change vs ${prior}`, String(cmp.netDelta)]);
    } else {
      const note = comparisonGateNote(cmp, monthLabelLong(report.month).split(' ')[0], prior);
      if (note) rows.push(['Comparison', note]);
    }
  }

  if ((report.unresolvedInflow ?? 0) > 0) rows.push(['Received, not counted as income yet', String(report.unresolvedInflow)]);
  if (report.complete === false) rows.push(['Note', PART_MONTH_NOTE]);

  rows.push([], ['Category', 'Amount'], ...report.categories.map((c) => [c.category, String(c.amount)]));

  // Only when the card would be drawn: a file that lists "Not identified" as nearly everything says
  // little, and the screen leaves it out for the same reason.
  const methods = methodBreakdown(report.paymentMethods);
  if (methods) {
    rows.push([], ['Spending by payment method'], ['Method', 'Amount'],
      ...methods.named.map((r) => [r.label, String(r.amount)]),
      ...(methods.unidentified ? [[methods.unidentified.label, String(methods.unidentified.amount)]] : []));
  }

  if (report.biggestSpends && report.biggestSpends.length > 0) {
    rows.push([], ['Biggest spends'], ['Date', 'Description', 'Category', 'Amount'],
      ...report.biggestSpends.map((s) => [s.date, s.label, s.category, String(s.amount)]));
  }
  if (report.dailySpend && report.dailySpend.length > 0) {
    rows.push([], ['Spend by day'], ['Date', 'Amount'], ...report.dailySpend.map((d) => [d.date, String(d.amount)]));
  }
  return rows;
}

export function toCsv(report: ReportData): string {
  return reportRows(report).map((r) => r.map(csvCell).join(',')).join('\n');
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** The sentences that qualify the month's figures, for a page with no screen around it. Each is the
 *  wording the report shows on screen, so the printed page never reads more certain than the app. */
function printableNotes(report: ReportData): string[] {
  const notes: string[] = [];
  const monthName = monthLabelLong(report.month).split(' ')[0];
  if (report.savingsRatePct != null) {
    // A negative rate reads as "Savings rate: -50% of income" if printed bare, which says less than
    // the screen does ("You spent 50% more than you earned").
    const size = oneDecimal(Math.abs(report.savingsRatePct));
    notes.push(
      size === '0' ? 'Spending matched income.'
        : report.savingsRatePct > 0 ? `Savings rate: ${size}% of income.`
          : `Spending was ${size}% more than income.`,
    );
  } else {
    const why = withheldRate(report.savingsRateGateReason);
    if (why) notes.push(`Savings rate. ${why}.`);
  }
  const cmp = report.comparison;
  if (cmp) {
    const prior = comparisonMonthName(cmp.previousMonth, report.month);
    if (cmp.reliable) {
      // "Income: No change vs August" and "Income: ↑ 11% vs August" both read; without the colon the
      // first would be "Income No change vs August".
      const income = deltaPhrase(cmp.incomeDeltaPct, prior);
      const expense = deltaPhrase(cmp.expenseDeltaPct, prior);
      const net = netChangePhrase(cmp, prior, fmtCurrency);
      const parts = [income && `Income: ${income}`, expense && `Expenses: ${expense}`, net && `Net: ${net}`].filter(Boolean);
      if (parts.length > 0) notes.push(`${parts.join('. ')}.`);
    } else {
      const gate = comparisonGateNote(cmp, monthName, prior);
      if (gate) notes.push(gate);
    }
  }
  if ((report.unresolvedInflow ?? 0) > 0) {
    notes.push(`${fmtCurrency(report.unresolvedInflow as number)} received isn't counted as income yet.`);
  }
  if (report.complete === false) notes.push(PART_MONTH_NOTE);
  return notes;
}

export function toPrintableHtml(report: ReportData): string {
  const net = report.income - report.expense;
  const categoryRows = report.categories
    .map(
      (c) =>
        `<tr><td>${escapeHtml(c.category)}</td><td class="num">${escapeHtml(fmtCurrency(c.amount))}</td></tr>`
    )
    .join('');
  const notes = printableNotes(report);
  const notesHtml = notes.length > 0
    ? `<ul class="notes">${notes.map((n) => `<li>${escapeHtml(n)}</li>`).join('')}</ul>`
    : '';
  const methods = methodBreakdown(report.paymentMethods);
  const methodsHtml = methods
    ? `<h2>Spending by payment method</h2><table>${[...methods.named, ...(methods.unidentified ? [methods.unidentified] : [])]
      .map((r) => `<tr><td>${escapeHtml(r.label)}</td><td class="num">${escapeHtml(fmtCurrency(r.amount))}</td></tr>`)
      .join('')}</table>`
    : '';
  const biggestHtml = report.biggestSpends && report.biggestSpends.length > 0
    ? `<h2>Biggest spends</h2><table>${report.biggestSpends
      .map((s) => `<tr><td>${escapeHtml(s.date)}</td><td>${escapeHtml(s.label)}<div class="sub">${escapeHtml(s.category)}</div></td><td class="num">${escapeHtml(fmtCurrency(s.amount))}</td></tr>`)
      .join('')}</table>`
    : '';
  const dailyHtml = report.dailySpend && report.dailySpend.length > 0
    ? `<h2>Spend by day</h2><table>${report.dailySpend
      .map((d) => `<tr><td>${escapeHtml(d.date)}</td><td class="num">${escapeHtml(fmtCurrency(d.amount))}</td></tr>`)
      .join('')}</table>`
    : '';

  // Self-contained: expo-print renders this in a WebView with no network and, on iOS, no access to
  // local asset URLs, so there is nothing to link out to -- styles inline, no images.
  return `<!DOCTYPE html>
<html>
<head><meta charset="utf-8" /><meta name="viewport" content="width=device-width, initial-scale=1" />
<style>
  body { font-family: -apple-system, Roboto, sans-serif; color: #1b1e2b; padding: 24px; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .sub { color: #6b7280; font-size: 12px; margin: 0 0 24px; }
  .totals { display: flex; gap: 12px; margin-bottom: 24px; }
  .total { flex: 1; border: 1px solid #e7e8f2; border-radius: 8px; padding: 12px; }
  .total .label { font-size: 10px; text-transform: uppercase; color: #6b7280; }
  .total .value { font-size: 18px; font-weight: 700; margin-top: 4px; }
  .income { color: #16a34a; } .expense { color: #dc2626; }
  h2 { font-size: 12px; text-transform: uppercase; color: #6b7280; margin: 24px 0 8px; }
  .notes { margin: 0 0 8px; padding-left: 18px; font-size: 13px; color: #374151; }
  .notes li { margin-bottom: 4px; }
  td .sub { color: #6b7280; font-size: 11px; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  td { padding: 8px 0; border-bottom: 1px solid #e7e8f2; }
  .num { text-align: right; }
  .empty { color: #6b7280; font-style: italic; font-size: 13px; }
</style>
</head>
<body>
  <h1>Fynora — ${escapeHtml(report.month)}</h1>
  <p class="sub">Monthly report</p>
  <div class="totals">
    <div class="total"><div class="label">Income</div><div class="value income">${escapeHtml(fmtCurrency(report.income))}</div></div>
    <div class="total"><div class="label">Expense</div><div class="value expense">${escapeHtml(fmtCurrency(report.expense))}</div></div>
    <div class="total"><div class="label">Net</div><div class="value">${escapeHtml(fmtCurrency(net))}</div></div>
  </div>
  ${notesHtml}
  <h2>Category Breakdown</h2>
  ${categoryRows ? `<table>${categoryRows}</table>` : '<p class="empty">No expenses recorded this month.</p>'}
  ${methodsHtml}
  ${biggestHtml}
  ${dailyHtml}
</body>
</html>`;
}

export async function shareCsv(report: ReportData): Promise<void> {
  const name = `fynora-report-${report.month}.csv`;
  const file = new File(Paths.cache, name);
  // A previous export of the same month leaves the file behind, and create() won't overwrite.
  if (file.exists) file.delete();
  file.create();
  file.write(toCsv(report));
  await shareFileAndCleanUp(file, {
    mimeType: 'text/csv', UTI: 'public.comma-separated-values-text', dialogTitle: name,
  });
}

export async function sharePdf(report: ReportData): Promise<void> {
  const { uri } = await Print.printToFileAsync({ html: toPrintableHtml(report) });
  const name = `fynora-report-${report.month}.pdf`;
  await shareFileAndCleanUp(new File(uri), { mimeType: 'application/pdf', UTI: 'com.adobe.pdf', dialogTitle: name });
}
