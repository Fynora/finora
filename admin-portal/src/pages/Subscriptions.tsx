import { useState } from 'react';
import { LoadError } from '../components/LoadError';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Users as UsersIcon, CheckCircle2, Clock, XCircle, Ban, Hourglass, PauseCircle } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { StatCard } from '../components/StatCard';
import { useNotify } from '../context/NotificationContext';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminSubscriptionsApi } from '../api/endpoints';
import type { SubscriptionSummaryDto } from '../types';

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

// D-28 PR4-A. Every plan a subscription can be manually moved to -- FREE is included so an admin
// can revert a manually-granted Plus/Premium, not just move upward.
const PLAN_CODES = ['FREE', 'PLUS', 'PREMIUM'];

const PAGE_SIZE = 20;

function SubscriptionsContent() {
  const [page, setPage] = useState(0);
  const [confirmingCancelFor, setConfirmingCancelFor] = useState<SubscriptionSummaryDto | null>(null);
  // Picking from the dropdown used to change the customer's plan on the spot. It now asks first,
  // and the admin can say why (the audit log used to get "Admin manual override" every time).
  const [pendingPlan, setPendingPlan] = useState<{ row: SubscriptionSummaryDto; planCode: string } | null>(null);
  const [planReason, setPlanReason] = useState('');
  // Viewing is SUBSCRIPTION_MANAGEMENT_VIEW; changing anything is _MANAGE on the backend. A
  // view-only admin used to see every control and get a refusal on each click.
  const canManage = useAdminAuth().hasPermission('SUBSCRIPTION_MANAGEMENT_MANAGE');
  const queryClient = useQueryClient();
  const notify = useNotify();
  const { data, isLoading, error: loadError } = useQuery({
    queryKey: ['admin-subscriptions', page],
    queryFn: () => adminSubscriptionsApi.list(page, PAGE_SIZE),
  });
  // Plan 3 review -- Subscription Health. A small stat row above the table, not folded into
  // `data` above: it's a platform-wide summary, unrelated to which page of the list is showing.
  const { data: health, isLoading: healthLoading, error: healthError } = useQuery({
    queryKey: ['admin-subscriptions-health'],
    queryFn: () => adminSubscriptionsApi.health(),
  });

  const changePlanMutation = useMutation({
    mutationFn: ({ userId, planCode, reason }: { userId: string; planCode: string; reason: string }) =>
      adminSubscriptionsApi.changePlan(userId, planCode, reason),
    onSettled: () => { setPendingPlan(null); setPlanReason(''); },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin-subscriptions'] });
      notify.success('Plan updated.');
    },
    onError: (err: any) => notify.error(errorMessage(err, 'Failed to change this plan.')),
  });

  // design spec §6.6 -- a Razorpay-backed subscription cannot be moved by the plain dropdown
  // (the backend already refuses it with 409); this is the confirm-then-retry flow that
  // dropdown's 409 error message alone left no way to actually act on.
  const cancelPaidMutation = useMutation({
    mutationFn: (userId: string) => adminSubscriptionsApi.cancelPaidSubscription(userId),
    onSuccess: () => {
      setConfirmingCancelFor(null);
      void queryClient.invalidateQueries({ queryKey: ['admin-subscriptions'] });
      notify.success("Paid subscription cancelled. You can now change this user's plan.");
    },
    onError: (err: any) => {
      setConfirmingCancelFor(null);
      notify.error(errorMessage(err, 'Failed to cancel this subscription.'));
    },
  });

  const columns: DataTableColumn<SubscriptionSummaryDto>[] = [
    {
      header: 'User',
      render: (s) => (
        <div className="flex items-center gap-2.5">
          <span className="w-7 h-7 rounded-lg bg-bg border border-border flex items-center justify-center flex-shrink-0">
            <UsersIcon size={13} className="text-muted" />
          </span>
          <div className="min-w-0">
            <p className="font-medium text-ink truncate">{s.userFullName ?? '(no name)'}</p>
            <p className="text-xs text-muted truncate">{s.userEmail}</p>
          </div>
        </div>
      ),
    },
    {
      header: 'Plan',
      render: (s) => {
        if (!canManage) return <span className="text-xs text-ink">{s.planCode ?? '—'}</span>;
        // Apple/Google own these mandates and offer no cancel to third parties, so the backend
        // refuses every manual change; a dropdown here could only ever fail.
        if (s.paymentProvider === 'REVENUECAT') {
          return (
            <div>
              <span className="text-xs text-ink">{s.planCode}</span>
              <p className="text-[11px] text-muted">Billed by Apple/Google. The user cancels in the store.</p>
            </div>
          );
        }
        return s.paymentProvider === 'RAZORPAY' ? (
          <div className="flex items-center gap-2">
            <span className="text-xs text-ink">{s.planCode}</span>
            {/* cancelPaidSubscription 404s for a PAUSED row (same findActiveOrTrial lookup as the
                user-facing cancel), so the user must resume it first. */}
            {s.status === 'PAUSED' ? (
              <span className="text-[11px] text-muted">Paused — resume to manage</span>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmingCancelFor(s)}
                className="text-[11px] font-semibold text-danger hover:underline"
              >
                Cancel paid subscription
              </button>
            )}
          </div>
        ) : (
          <select
            aria-label={`Plan for ${s.userEmail ?? s.userFullName ?? 'this user'}`}
            value={s.planCode ?? ''}
            disabled={changePlanMutation.isPending}
            onChange={(e) => { if (e.target.value !== s.planCode) setPendingPlan({ row: s, planCode: e.target.value }); }}
            className="text-xs border border-border rounded-lg px-2 py-1.5 bg-card text-ink"
          >
            {PLAN_CODES.map((code) => (
              <option key={code} value={code}>{code}</option>
            ))}
          </select>
        );
      },
    },
    { header: 'Status', render: (s) => s.status, cellClassName: 'text-muted' },
    { header: 'Start date', render: (s) => s.startDate, cellClassName: 'text-muted' },
    { header: 'Renewal date', render: (s) => s.renewalDate ?? '—', cellClassName: 'text-muted' },
  ];

  return (
    <div className="space-y-4">
      <LoadError error={healthError} />
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
        <StatCard icon={CheckCircle2} label="Active" value={healthLoading ? '…' : health?.activeCount ?? 0} tone="success" />
        <StatCard icon={Clock} label="Past due" value={healthLoading ? '…' : health?.pastDueCount ?? 0} tone="warning" />
        <StatCard icon={XCircle} label="Payment failed" value={healthLoading ? '…' : health?.paymentFailedCount ?? 0} tone="warning" />
        <StatCard icon={Ban} label="Cancelled" value={healthLoading ? '…' : health?.cancelledCount ?? 0} />
        <StatCard icon={Hourglass} label="Pending orders" value={healthLoading ? '…' : health?.pendingOrderCount ?? 0} tone="warning" />
        <StatCard icon={PauseCircle} label="Paused" value={healthLoading ? '…' : health?.pausedCount ?? 0} tone="warning" />
      </div>
      <p className="text-sm text-muted max-w-xl">
        Every user's current plan. A Razorpay-backed subscription must be cancelled here before its
        plan can be changed manually -- see design spec §6.6.
      </p>
      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        keyFor={(s) => s.subscriptionId}
        loading={isLoading}
        error={loadError}
        emptyMessage="No subscriptions yet."
      />
      {data && (
        <Pagination
          page={page}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
      {pendingPlan && (
        <ConfirmDialog
          title={`Change this plan to ${pendingPlan.planCode}?`}
          message={`${pendingPlan.row.userEmail ?? 'This user'} moves from ${pendingPlan.row.planCode ?? 'no plan'} to ${pendingPlan.planCode} immediately.`}
          confirmLabel="Change plan"
          busy={changePlanMutation.isPending}
          onConfirm={() => changePlanMutation.mutate({
            userId: pendingPlan.row.userId,
            planCode: pendingPlan.planCode,
            reason: planReason.trim() || 'Admin manual override',
          })}
          onCancel={() => { setPendingPlan(null); setPlanReason(''); }}
        >
          <label htmlFor="plan-change-reason" className="text-xs font-medium text-muted mb-1 mt-3 block">Reason for the audit log (optional)</label>
          <input
            id="plan-change-reason"
            value={planReason}
            maxLength={200}
            onChange={(e) => setPlanReason(e.target.value)}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </ConfirmDialog>
      )}
      {confirmingCancelFor && (
        <ConfirmDialog
          title="Cancel this user's paid subscription?"
          message={`This immediately stops ${confirmingCancelFor.userEmail ?? 'this user'}'s Razorpay subscription. You can then change their plan manually.`}
          confirmLabel="Confirm"
          danger
          busy={cancelPaidMutation.isPending}
          onConfirm={() => cancelPaidMutation.mutate(confirmingCancelFor.userId)}
          onCancel={() => setConfirmingCancelFor(null)}
        />
      )}
    </div>
  );
}

export default function Subscriptions() {
  return (
    <AdminLayout title="Subscriptions" subtitle="Manage user plans -- Free, Plus, Premium">
      <RequirePermission permission="SUBSCRIPTION_MANAGEMENT_VIEW">
        <SubscriptionsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
