import { useQuery } from '@tanstack/react-query';
import { DataTable, type DataTableColumn } from './DataTable';
import { adminPlatformAnalyticsApi } from '../api/endpoints';
import { SPENDING_TRACKING_LABELS } from '../lib/activationLabels';

/** Each answer's share of everyone who has answered, to one decimal place; a dash before anyone has. */
function shareOf(count: number, answered: number) {
  return answered === 0 ? '—' : `${((count / answered) * 100).toFixed(1)}%`;
}

/** The required setup question "How do you keep track of your spending today?", across live
 *  customer accounts. Shown on Activation, beside the same answers broken down by step. */
export function SpendingTrackingAnswers() {
  const tracking = useQuery({
    queryKey: ['admin-spending-tracking'],
    queryFn: () => adminPlatformAnalyticsApi.spendingTracking(),
  });
  const answered = tracking.data?.answered ?? 0;
  const columns: DataTableColumn<{ method: string; count: number }>[] = [
    {
      header: 'Answer',
      render: (m) => <span className="font-medium text-ink">{SPENDING_TRACKING_LABELS[m.method] ?? m.method}</span>,
    },
    { header: 'Users', render: (m) => m.count, cellClassName: 'text-muted' },
    { header: 'Share of answers', render: (m) => shareOf(m.count, answered), cellClassName: 'text-muted' },
  ];

  return (
    <section>
      <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-1">How users tracked spending before Fynora</h2>
      <p className="text-sm text-muted mb-3">
        Answers to the required setup question, across live user accounts, all time.
        {tracking.data && ` ${tracking.data.answered} answered, ${tracking.data.notAnswered} not yet asked or not yet answered.`}
      </p>
      <DataTable
        columns={columns}
        rows={tracking.data?.methods}
        keyFor={(m) => m.method}
        loading={tracking.isLoading}
        error={tracking.error}
        emptyMessage="No answers yet."
      />
    </section>
  );
}
