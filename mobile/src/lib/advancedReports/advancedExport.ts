import type { AdvancedReport } from '../../api/endpoints';
import { methodBreakdown } from '../reportSections/methodBreakdown';
import { againstName, coverageNote, gateNote, incompleteNote, leftOutNote, periodSpan } from './advancedFormat';

/** One decimal, so 12.34 reads as 12.3 and a whole number stays whole. */
const oneDecimal = (n: number) => Math.round(n * 10) / 10;

type Row = (string | number)[];

function withheldRate(reason: string | null): string | null {
  switch (reason) {
    case 'NO_INCOME': return 'Not shown: no income was counted in this period';
    case 'UNRESOLVED_EXCEEDS_INCOME': return 'Not shown: money received has not been sorted into income yet';
    default: return null;
  }
}

/**
 * The period as CSV rows. A file has no screen around it to explain a figure, so every caveat the
 * page shows is a row here too: the months covered, a month left out, months only partly in the
 * data, a comparison the server withheld, a savings rate it withheld, credits not counted as income
 * yet. A section the page has no data for is left out whole rather than written as an empty header.
 * Investments are in no figure, as on the page.
 */
export function advancedCsvRows(report: AdvancedReport): Row[] {
  const { period, totals, comparison: c } = report;
  const rows: Row[] = [
    ['Period', periodSpan(period) ?? ''],
    ['Months in the period', period.monthCount],
  ];
  const notes = [coverageNote(period), leftOutNote(period), incompleteNote(period)].filter((n): n is string => !!n);
  for (const n of notes) rows.push(['Note', n]);
  rows.push(['Note', 'Investment transfers are not counted in any figure.']);

  rows.push([], ['Income', totals.income], ['Expense', totals.expense], ['Net', totals.net]);
  if (totals.avgMonthlyExpense != null) rows.push(['Average monthly expense', Math.round(totals.avgMonthlyExpense)]);
  if (totals.savingsRatePct != null) rows.push(['Savings rate (%)', oneDecimal(totals.savingsRatePct)]);
  else {
    const why = withheldRate(totals.savingsRateGateReason);
    if (why) rows.push(['Savings rate', why]);
  }
  if (totals.unresolvedInflow > 0) rows.push(['Received, not counted as income yet', totals.unresolvedInflow]);

  if (c.reliable) {
    const prior = againstName(period);
    if (c.incomeDeltaPct != null) rows.push([`Income change vs ${prior} (%)`, oneDecimal(c.incomeDeltaPct)]);
    if (c.expenseDeltaPct != null) rows.push([`Expense change vs ${prior} (%)`, oneDecimal(c.expenseDeltaPct)]);
    if (c.netDelta != null) rows.push([`Net change vs ${prior}`, c.netDelta]);
    if (c.savingsRateDeltaPp != null) rows.push([`Savings rate change vs ${prior} (points)`, oneDecimal(c.savingsRateDeltaPp)]);
  } else {
    const note = gateNote(c, period);
    if (note) rows.push(['Comparison', note]);
  }

  rows.push([], ['Month', 'Income', 'Expense', 'Net', 'Savings rate (%)', 'Whole month'],
    ...report.months.map((m): Row => [
      m.month, m.income, m.expense, m.net, m.savingsRatePct == null ? '' : oneDecimal(m.savingsRatePct), m.complete ? 'Yes' : 'No',
    ]));

  if (report.categories.rows.length > 0) {
    rows.push([], ['Category', 'Amount', 'Share (%)', 'Earlier period', 'Change'],
      ...report.categories.rows.map((cat): Row => [
        cat.name, cat.amount, oneDecimal(cat.sharePct), cat.previousAmount ?? '', cat.changeAmount ?? '',
      ]));
    if (report.categories.otherAmount > 0) {
      rows.push([`Other (${report.categories.categoryCount - report.categories.rows.length} more)`, report.categories.otherAmount, oneDecimal(report.categories.otherSharePct), '', '']);
    }
  }

  if (report.merchants.rows.length > 0) {
    rows.push([], ['Merchant', 'Amount', 'Share (%)', 'Purchases', 'Earlier period', 'Change'],
      ...report.merchants.rows.map((m): Row => [
        m.name, m.amount, oneDecimal(m.sharePct), m.transactionCount, m.previousAmount ?? '', m.changeAmount ?? '',
      ]));
    rows.push(['Note', `${Math.round(report.merchants.attributedSharePct)}% of spending is tied to a named merchant.`]);
  }

  const methods = methodBreakdown(report.paymentMethods);
  if (methods) {
    rows.push([], ['Spending by payment method'], ['Method', 'Amount'],
      ...methods.named.map((r): Row => [r.label, r.amount]),
      ...(methods.unidentified ? [[methods.unidentified.label, methods.unidentified.amount] as Row] : []));
  }

  if (report.income.sources.length > 0) {
    rows.push([], ['Income source', 'Amount', 'Share (%)'],
      ...report.income.sources.map((s): Row => [s.label, s.amount, oneDecimal(s.sharePct)]));
  }

  if (report.unusualSpends.length > 0) {
    rows.push([], ['Larger than usual for the category'], ['Date', 'Description', 'Category', 'Amount', 'Usual amount'],
      ...report.unusualSpends.map((u): Row => [u.date, u.label, u.category, u.amount, u.typicalAmount]));
  }

  if (report.insights.length > 0) {
    rows.push([], ['Key insights'], ...report.insights.map((i): Row => [i.headline, i.detail]));
  }
  return rows;
}

/** The file name for a period's export. */
export function advancedExportName(report: AdvancedReport): string {
  return `fynora-advanced-report-${report.period.startMonth ?? 'none'}-to-${report.period.endMonth ?? 'none'}.csv`;
}
