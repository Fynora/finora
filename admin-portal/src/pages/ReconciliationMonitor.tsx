import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, Copy, ArrowLeftRight, Undo2, Repeat, Layers, RotateCcw, TrendingUp, History } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { StatCard } from '../components/StatCard';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminReconciliationApi } from '../api/endpoints';
import { loadErrorMessage } from '../lib/loadError';

/**
 * Platform-wide Reconciliation Monitor -- ReconciliationService has always run fully
 * automatically (after every import/create/edit/delete, no manual trigger anywhere in this
 * codebase), so there's nothing to manage here, only to observe. Per-user reconciliation +
 * Workspace Health visibility lives on that account's own Users detail page (WorkspaceSection),
 * proxying the same WorkspaceDashboardService.summarize() the self-service Workspace Dashboard
 * used before this moved off the User Portal's main nav.
 */
function ReconciliationMonitorContent() {
  const { data: stats, isLoading, error } = useQuery({
    queryKey: ['admin-reconciliation-stats'],
    queryFn: () => adminReconciliationApi.platformStats(),
  });

  const intro = (
    <p className="text-sm text-muted max-w-xl">
      Every transaction on the platform, broken down by how reconciliation resolved it. OK is
      everything left untouched; the rest were automatically flagged as a likely duplicate,
      internal transfer, refund, reversal, investment transfer, or superseded by a re-uploaded
      statement, and excluded from spend totals. To investigate one specific account, open it
      from Users and use the Workspace section there.
    </p>
  );

  // A failed request used to render every card as 0, which reads as "nothing was flagged".
  if (!isLoading && error != null && !stats) {
    return (
      <div className="space-y-6">
        {intro}
        <p role="alert" className="text-sm text-danger">{loadErrorMessage(error, 'these numbers')}</p>
      </div>
    );
  }

  const value = (n: number | undefined) => (isLoading ? '…' : n ?? 0);

  return (
    <div className="space-y-6">
      {intro}

      {/* The status cards partition the total. Recurring is a separate flag on transactions of any
          status, so it sits below as "of these" instead of as a tenth card that made the grid add
          up to more than the total. */}
      <ul aria-label="Transactions by reconciliation status" className="grid grid-cols-2 md:grid-cols-3 gap-4">
        <li><StatCard icon={Layers} label="Total transactions" value={value(stats?.totalTransactions)} /></li>
        <li><StatCard icon={CheckCircle2} label="OK" value={value(stats?.okCount)} tone="success" /></li>
        <li><StatCard icon={Copy} label="Duplicates" value={value(stats?.duplicateCount)} tone="warning" /></li>
        <li><StatCard icon={ArrowLeftRight} label="Transfers" value={value(stats?.transferCount)} tone="warning" /></li>
        <li><StatCard icon={Undo2} label="Refunds" value={value(stats?.refundCount)} tone="warning" /></li>
        <li><StatCard icon={RotateCcw} label="Reversals" value={value(stats?.reversalCount)} tone="warning" /></li>
        <li><StatCard icon={TrendingUp} label="Investment transfers" value={value(stats?.investmentTransferCount)} tone="warning" /></li>
        <li><StatCard icon={History} label="Superseded" value={value(stats?.supersededCount)} tone="warning" /></li>
      </ul>

      {stats && (
        <p className="flex items-center gap-2 text-sm text-muted">
          <Repeat size={15} className="flex-shrink-0" />
          <span>
            <span className="font-mono font-semibold text-ink">{stats.recurringCount.toLocaleString()}</span> of these are
            also marked recurring. Recurring is a label, not a reconciliation outcome, and stays in spend totals.
          </span>
        </p>
      )}
    </div>
  );
}

export default function ReconciliationMonitor() {
  return (
    <AdminLayout title="Reconciliation Monitor" subtitle="Platform-wide reconciliation outcomes, aggregated across every user">
      <RequirePermission permission="RECONCILIATION_VIEW">
        <ReconciliationMonitorContent />
      </RequirePermission>
    </AdminLayout>
  );
}
