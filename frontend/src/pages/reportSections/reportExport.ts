import type { ReportData } from '../../api/endpoints';
import { comparisonGateNote, comparisonMonthName } from '../../lib/reportComparison';
import { monthLabelLong } from './reportFormat';

/** One decimal, so 12.34 reads as 12.3 and a whole number stays whole. */
const oneDecimal = (n: number) => Math.round(n * 10) / 10;

/** Why the savings rate is not in the file, in the words the report itself uses. */
function withheldRate(reason: string | null | undefined): string | null {
  switch (reason) {
    case 'NO_INCOME': return 'Not shown: no income was counted this month';
    case 'UNRESOLVED_EXCEEDS_INCOME': return 'Not shown: money received has not been sorted into income yet';
    default: return null;
  }
}

/**
 * The month as CSV rows: the totals and what qualifies them, then the categories, the biggest spends
 * and the spend per day. A file has no screen around it to explain a figure, so every caveat the
 * report shows on screen is a row here too: a month only partly in the data, money received that is
 * not counted as income yet, a savings rate the server withheld, a comparison it did not trust. A
 * section the report has no data for (an older server, a month with nothing spent) is left out whole
 * rather than written as an empty header.
 */
export function reportCsvRows(report: ReportData): (string | number)[][] {
  const rows: (string | number)[][] = [
    ['Month', report.month],
    ['Income', report.income],
    ['Expense', report.expense],
    ['Net', report.income - report.expense],
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
      if (cmp.netDelta != null) rows.push([`Net change vs ${prior}`, cmp.netDelta]);
    } else {
      const note = comparisonGateNote(cmp, monthLabelLong(report.month).split(' ')[0], prior);
      if (note) rows.push(['Comparison', note]);
    }
  }

  if ((report.unresolvedInflow ?? 0) > 0) {
    rows.push(['Received, not counted as income yet', report.unresolvedInflow!]);
  }
  if (report.complete === false) {
    rows.push(['Note', "Part of this month isn't in yet, so these figures may change."]);
  }

  rows.push([], ['Category', 'Amount'], ...report.categories.map((c): (string | number)[] => [c.category, c.amount]));

  if (report.biggestSpends && report.biggestSpends.length > 0) {
    rows.push([], ['Biggest spends'], ['Date', 'Description', 'Category', 'Amount'],
      ...report.biggestSpends.map((s): (string | number)[] => [s.date, s.label, s.category, s.amount]));
  }
  if (report.dailySpend && report.dailySpend.length > 0) {
    rows.push([], ['Spend by day'], ['Date', 'Amount'],
      ...report.dailySpend.map((d): (string | number)[] => [d.date, d.amount]));
  }
  return rows;
}
