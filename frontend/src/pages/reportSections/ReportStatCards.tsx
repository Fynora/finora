import { ArrowDownCircle, ArrowUpCircle } from 'lucide-react';
import type { ReportData } from '../../api/endpoints';
import { MetricCard } from '../../design-system';
import { comparisonMonthName } from '../../lib/reportComparison';
import { fmt } from './reportFormat';

/**
 * Income and Expenses, each with how it moved since the month before. Every figure here is the
 * server's: when it did not trust the comparison, the delta line is left off and the hero above
 * says why in words. Net is not a card of its own: the hero carries it, as an amount.
 */
export function ReportStatCards({ report }: { report: ReportData }) {
  const cmp = report.comparison;
  const priorName = cmp ? comparisonMonthName(cmp.previousMonth, report.month) : null;
  const reliable = !!cmp?.reliable;
  // MetricCard shows a delta line only when it is handed a label. An untrusted comparison (or no
  // comparison at all, for a user with no live account) gets none: the hero above already says why
  // there is no comparison, and a second "— vs August / Why?" under each card only repeats it.
  const deltaLabel = reliable && priorName ? `vs ${priorName}` : undefined;

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <MetricCard
        variant="elevated" label="Total income" value={fmt(report.income)}
        icon={ArrowDownCircle} iconBg="bg-success-bg" iconColor="text-success" valueColor="text-success"
        delta={reliable ? cmp!.incomeDeltaPct : null} deltaLabel={deltaLabel}
      />
      <MetricCard
        variant="elevated" label="Total expenses" value={fmt(report.expense)}
        icon={ArrowUpCircle} iconBg="bg-danger-bg" iconColor="text-danger" valueColor="text-danger"
        delta={reliable ? cmp!.expenseDeltaPct : null} deltaLabel={deltaLabel}
        invertDelta
      />
    </div>
  );
}
