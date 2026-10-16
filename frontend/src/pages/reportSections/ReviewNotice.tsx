import { Link } from 'react-router-dom';
import type { ReportData } from '../../api/endpoints';
import { fmt, lastDayOf } from './reportFormat';

/**
 * Money in that is not counted as income yet (a transfer from a person, an unexplained card credit).
 * Said once, here, with one way to sort it out -- never added to income or expense, and never the
 * same thing as a spending category called "Uncategorized". Absent when there is none.
 */
export function ReviewNotice({ report }: { report: ReportData }) {
  const amount = report.unresolvedInflow ?? 0;
  if (amount <= 0) return null;
  const count = report.unresolvedInflowCount;
  // An older server sends no count, so the sentence still reads whole without one.
  const what = count != null && count > 0
    ? `${count} ${count === 1 ? 'credit' : 'credits'} worth ${fmt(amount)} ${count === 1 ? "isn't" : "aren't"}`
    : `${fmt(amount)} received isn't`;
  return (
    <div
      data-testid="review-notice"
      className="flex flex-wrap items-center justify-between gap-3 rounded-xl2 border border-warning/30 bg-warning-bg px-4 py-3"
    >
      <p className="text-sm text-warning">{what} counted as income yet.</p>
      <Link
        to={`/app/money-review?start=${report.month}-01&end=${lastDayOf(report.month)}`}
        className="whitespace-nowrap rounded-lg border border-primary px-3 py-1.5 text-xs font-semibold text-primary hover:bg-primary-light"
      >
        Review money
      </Link>
    </div>
  );
}
