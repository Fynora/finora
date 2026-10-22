import type { AdvancedReport } from '../../api/endpoints';
import { fmt } from '../reportSections/reportFormat';
import { methodBreakdown } from '../reportSections/methodBreakdown';
import { advancedExportName, withheldRate } from './advancedExport';
import {
  againstName, coverageNote, gateNote, incompleteNote, leftOutNote, monthYear, percentChange, periodSpan, pointsChange,
  signedAmount,
} from './advancedFormat';

/**
 * The period as a printable page, for "Export PDF" (mobile) and "Print / PDF" (web). The same file lives at
 * mobile/src/lib/advancedReports/advancedPrint.ts and frontend/src/pages/advancedReports/advancedPrint.ts,
 * differing only in where the currency formatter is imported from; keep the two in step.
 *
 * It holds what the CSV holds, laid out to read: the screen spreads it over five tabs and charts, and
 * a print of the screen would carry only the tab that happened to be open. Every caveat the page shows
 * is printed too, so the paper never reads more certain than the app. Every figure, name and sentence
 * is the server's or the page's own wording.
 *
 * Self-contained: styles inline, no images, scripts or links. expo-print renders it in a WebView with
 * no network, and the web page drops it into its own document for the browser's print dialog.
 * Category, merchant and income-source names are user-controlled, so every value is escaped.
 */

function esc(value: string | number): string {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** One decimal, so 12.34 reads as 12.3 and a whole number stays whole. */
const pct = (n: number) => `${Math.round(n * 10) / 10}%`;

/** "2026-09-03" -> "3 Sep 2026". Built on monthYear (en-US month names) because Intl's en-GB and
 *  en-IN write September as "Sept"; the day is read straight from the string, never through a Date. */
function dayLabel(iso: string): string {
  return `${Number(iso.slice(8, 10))} ${monthYear(iso.slice(0, 7))}`;
}

function table(head: string[], rows: string[][], numeric: number[]): string {
  const cell = (tag: 'th' | 'td', i: number, v: string) => `<${tag}${numeric.includes(i) ? ' class="num"' : ''}>${v}</${tag}>`;
  return `<table><thead><tr>${head.map((h, i) => cell('th', i, esc(h))).join('')}</tr></thead>`
    + `<tbody>${rows.map((r) => `<tr>${r.map((v, i) => cell('td', i, v)).join('')}</tr>`).join('')}</tbody></table>`;
}

const section = (title: string, inner: string) => `<h2>${esc(title)}</h2>${inner}`;

/** The scoped stylesheet. Every rule sits under .fy-ar, so on web it cannot restyle the page around it. */
export const ADVANCED_PRINT_CSS = `
.fy-ar { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; color: #1b1e2b; background: #fff; font-size: 13px; line-height: 1.4;
  -webkit-print-color-adjust: exact; print-color-adjust: exact; }
.fy-ar h1 { font-size: 20px; margin: 0 0 4px; }
.fy-ar .sub { color: #6b7280; font-size: 12px; margin: 0 0 16px; }
.fy-ar .notes { margin: 0 0 16px; padding-left: 18px; color: #374151; }
.fy-ar .notes li { margin-bottom: 4px; }
.fy-ar .kpis { display: flex; flex-wrap: wrap; gap: 8px; }
.fy-ar .kpi { flex: 1 1 30%; min-width: 140px; border: 1px solid #e7e8f2; border-radius: 8px; padding: 10px; break-inside: avoid; }
.fy-ar .kpi .label { font-size: 10px; text-transform: uppercase; color: #6b7280; }
.fy-ar .kpi .value { font-size: 17px; font-weight: 700; margin-top: 2px; font-variant-numeric: tabular-nums; }
.fy-ar .chg { font-size: 11px; color: #374151; margin-top: 2px; }
.fy-ar .income { color: #16a34a; }
.fy-ar .expense { color: #dc2626; }
.fy-ar h2 { font-size: 12px; text-transform: uppercase; color: #6b7280; margin: 22px 0 6px; break-after: avoid; }
.fy-ar table { width: 100%; border-collapse: collapse; }
.fy-ar th { text-align: left; font-size: 10px; text-transform: uppercase; color: #6b7280; font-weight: 600; padding: 6px 4px; border-bottom: 1px solid #d1d5db; }
.fy-ar td { padding: 6px 4px; border-bottom: 1px solid #e7e8f2; vertical-align: top; }
.fy-ar tr { break-inside: avoid; }
.fy-ar .num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.fy-ar .tag { margin-left: 6px; font-size: 10px; color: #92400e; background: #fef3c7; border-radius: 4px; padding: 1px 4px; }
.fy-ar .small { font-size: 11px; color: #6b7280; margin: 4px 0 0; }
.fy-ar .insights { padding-left: 18px; margin: 0; }
.fy-ar .insights li { margin-bottom: 8px; break-inside: avoid; }
`;

/** The report as an HTML fragment, wrapped in one .fy-ar element. */
export function advancedPrintBody(report: AdvancedReport): string {
  const { period, totals, comparison: c } = report;
  const against = againstName(period);
  const span = periodSpan(period) ?? '';
  const change = (text: string | null) => (c.reliable && text ? `<div class="chg">${esc(text)} vs ${esc(against)}</div>` : '');

  const rateWhy = totals.savingsRatePct == null ? withheldRate(totals.savingsRateGateReason) : null;
  const notes = [
    coverageNote(period), leftOutNote(period), incompleteNote(period), gateNote(c, period),
    // Said as Reports' PDF says it: on paper "Not shown: ..." alone does not name what is not shown.
    rateWhy ? `Savings rate. ${rateWhy}.` : null,
    totals.unresolvedInflow > 0 ? `${fmt(totals.unresolvedInflow)} received is not counted as income yet.` : null,
    'Investment transfers are not counted in any figure.',
  ].filter((n): n is string => !!n);

  const netChange = c.netDelta == null ? null
    : Math.round(c.netDelta) === 0 ? 'No change' : `${c.netDelta > 0 ? '↑' : '↓'} ${fmt(Math.abs(c.netDelta))}`;
  const kpi = (label: string, value: string, cls: string, chg: string) =>
    `<div class="kpi"><div class="label">${esc(label)}</div><div class="value${cls ? ` ${cls}` : ''}">${esc(value)}</div>${chg}</div>`;
  const kpis = [
    kpi('Total income', fmt(totals.income), 'income', change(percentChange(c.incomeDeltaPct))),
    kpi('Total expenses', fmt(totals.expense), 'expense', change(percentChange(c.expenseDeltaPct))),
    kpi('Net cash flow', fmt(totals.net), totals.net >= 0 ? 'income' : 'expense', change(netChange)),
    totals.avgMonthlyExpense != null
      ? kpi('Average monthly spend', fmt(totals.avgMonthlyExpense), '', change(percentChange(c.avgMonthlyExpenseDeltaPct)))
      : '',
    // A withheld rate gets no change line: "↓ 3 pp" under a dash would compare a figure nobody can see.
    totals.savingsRatePct == null
      ? kpi('Savings rate', '—', '', '')
      : kpi('Savings rate', pct(totals.savingsRatePct), '', change(pointsChange(c.savingsRateDeltaPp))),
  ].join('');

  const parts: string[] = [
    `<h1>Fynora — Advanced report</h1>`,
    `<p class="sub">${esc(span)}${c.reliable ? ` · Compared with ${esc(against)}` : ''}</p>`,
    `<ul class="notes">${notes.map((n) => `<li>${esc(n)}</li>`).join('')}</ul>`,
    `<div class="kpis">${kpis}</div>`,
  ];

  if (report.months.length > 0) {
    parts.push(section('Month by month', table(
      ['Month', 'Income', 'Expenses', 'Net', 'Savings rate'],
      report.months.map((m) => [
        `${esc(monthYear(m.month))}${m.complete ? '' : '<span class="tag">Part month</span>'}`,
        esc(fmt(m.income)), esc(fmt(m.expense)), esc(fmt(m.net)), m.savingsRatePct == null ? '—' : esc(pct(m.savingsRatePct)),
      ]),
      [1, 2, 3, 4],
    )));
  }

  const cats = report.categories;
  if (cats.rows.length > 0) {
    const rows = cats.rows.map((r) => [
      esc(r.name), esc(fmt(r.amount)), esc(pct(r.sharePct)), r.changeAmount == null ? '—' : esc(signedAmount(r.changeAmount, fmt)),
    ]);
    if (cats.otherAmount > 0) {
      rows.push([esc(`Other (${cats.categoryCount - cats.rows.length} more)`), esc(fmt(cats.otherAmount)), esc(pct(cats.otherSharePct)), '']);
    }
    parts.push(section('Spending by category', table(['Category', 'Amount', 'Share', 'Change'], rows, [1, 2, 3])));
  }

  const merchants = report.merchants;
  if (merchants.rows.length > 0) {
    parts.push(section('Merchants', table(
      ['Merchant', 'Amount', 'Share', 'Purchases', 'Change'],
      merchants.rows.map((m) => [
        esc(m.name), esc(fmt(m.amount)), esc(pct(m.sharePct)), esc(m.transactionCount),
        m.changeAmount == null ? '—' : esc(signedAmount(m.changeAmount, fmt)),
      ]),
      [1, 2, 3, 4],
    ) + `<p class="small">${esc(`${Math.round(merchants.attributedSharePct)}% of spending is tied to a named merchant.`)}</p>`));
  }

  const methods = methodBreakdown(report.paymentMethods);
  if (methods) {
    const rows = [...methods.named, ...(methods.unidentified ? [methods.unidentified] : [])];
    parts.push(section('Spending by payment method', table(['Method', 'Amount'], rows.map((r) => [esc(r.label), esc(fmt(r.amount))]), [1])));
  }

  if (report.income.sources.length > 0) {
    parts.push(section('Income sources', table(
      ['Source', 'Amount', 'Share'],
      report.income.sources.map((s) => [esc(s.label), esc(fmt(s.amount)), esc(pct(s.sharePct))]),
      [1, 2],
    )));
  }

  const intl = report.international;
  if (intl.transactionCount > 0) {
    const summary = `<p class="small">${esc(`Total ${fmt(intl.totalSpend)} across ${intl.transactionCount} transactions: `
      + `${fmt(intl.purchasesSpend)} foreign purchases, ${fmt(intl.otherChargesSpend)} other charges (GST, FX markup and anything billed in rupees).`)}</p>`;
    const byCurrency = intl.byCurrency.length > 0
      ? table(['Currency', 'Billed in rupees', 'Transactions'], intl.byCurrency.map((x) => [esc(x.currency), esc(fmt(x.rupeeTotal)), esc(x.transactionCount)]), [1, 2])
      : '';
    parts.push(section('International spend', byCurrency + summary));
  }

  if (report.unusualSpends.length > 0) {
    parts.push(section('Larger than usual for the category', table(
      ['Date', 'Description', 'Category', 'Amount', 'Usual'],
      report.unusualSpends.map((u) => [esc(dayLabel(u.date)), esc(u.label), esc(u.category), esc(fmt(u.amount)), esc(fmt(u.typicalAmount))]),
      [3, 4],
    )));
  }

  if (report.insights.length > 0) {
    parts.push(section('Key insights', `<ul class="insights">${report.insights
      .map((i) => `<li><strong>${esc(i.headline)}</strong><div>${esc(i.detail)}</div></li>`).join('')}</ul>`));
  }

  return `<div class="fy-ar">${parts.join('')}</div>`;
}

/** A whole page around the body, for expo-print. */
export function advancedPrintDocument(report: AdvancedReport): string {
  return `<!DOCTYPE html>
<html>
<head><meta charset="utf-8" /><meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${esc(advancedPdfName(report))}</title>
<style>body { margin: 0; padding: 24px; background: #fff; }${ADVANCED_PRINT_CSS}</style>
</head>
<body>${advancedPrintBody(report)}</body>
</html>`;
}

/** The PDF's file name: the CSV's, with the PDF extension. */
export function advancedPdfName(report: AdvancedReport): string {
  return advancedExportName(report).replace(/\.csv$/, '.pdf');
}
