import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminUserCategoriesApi, adminUserMerchantsApi } from '../../api/endpoints';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { LoadError } from '../../components/LoadError';
import { useNotify } from '../../context/NotificationContext';
import { formatIst } from '../../lib/istTime';
import type { TransactionDto } from '../../types';
import { errorMessage } from './errorMessage';

/** How many of the merchant's transactions show before "Show all". */
const FIRST_TRANSACTIONS = 20;

function rupees(t: TransactionDto) {
  return `${t.type === 'EXPENSE' ? '−' : '+'}₹${Math.abs(t.amount).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/** A learning event's action as a sentence fragment, e.g. CATEGORY_CONFIRMED -> "Category confirmed". */
function actionLabel(action: string) {
  const words = action.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * One merchant of one customer, opened from the Merchants section (2026-11-12): what the engine
 * has learned for it, every learning event, and the transactions attributed to it. From a
 * transaction an admin can set its category on the customer's behalf -- the merchant-centric
 * confirm (POST .../confirm-category), which also teaches the merchant that category, so it asks
 * first and says so.
 */
export function MerchantDetailPanel({ userId, merchantId, merchantName, canManage }: {
  userId: string;
  merchantId: string;
  merchantName: string;
  canManage: boolean;
}) {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const [showAll, setShowAll] = useState(false);
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [confirming, setConfirming] = useState<{ txn: TransactionDto; categoryId: string } | null>(null);

  const detail = useQuery({
    queryKey: ['admin-user-merchant', userId, merchantId],
    queryFn: () => adminUserMerchantsApi.get(userId, merchantId),
  });
  const history = useQuery({
    queryKey: ['admin-user-merchant-audit', userId, merchantId],
    queryFn: () => adminUserMerchantsApi.audit(userId, merchantId),
  });
  const transactions = useQuery({
    queryKey: ['admin-user-merchant-transactions', userId, merchantId],
    queryFn: () => adminUserMerchantsApi.transactions(userId, merchantId),
  });
  const categories = useQuery({
    queryKey: ['admin-user-categories', userId],
    queryFn: () => adminUserCategoriesApi.list(userId),
    enabled: canManage,
  });

  const confirmMutation = useMutation({
    mutationFn: ({ txn, categoryId }: { txn: TransactionDto; categoryId: string }) =>
      adminUserMerchantsApi.confirmCategory(userId, merchantId, { categoryId, applyToTransactionId: txn.id }),
    onSuccess: (_merchant, { txn }) => {
      setConfirming(null);
      setChosen((c) => ({ ...c, [txn.id]: '' }));
      for (const key of [
        ['admin-user-merchant', userId, merchantId], ['admin-user-merchant-audit', userId, merchantId],
        ['admin-user-merchant-transactions', userId, merchantId], ['admin-user-merchants', userId],
        ['admin-user-transactions', userId], ['admin-user-learning-summary', userId],
        ['admin-user-learning-timeline', userId],
      ]) void queryClient.invalidateQueries({ queryKey: key });
      notify.success('Category set.');
    },
    onError: (err: unknown) => {
      setConfirming(null);
      notify.error(errorMessage(err, 'Could not set this category.'));
    },
  });

  const txns = transactions.data ?? [];
  const shown = showAll ? txns : txns.slice(0, FIRST_TRANSACTIONS);
  const categoryName = (id: string) => categories.data?.find((c) => c.id === id)?.name ?? 'this category';

  return (
    <div className="mt-2 mb-1 rounded-lg border border-border bg-bg p-4 space-y-5" data-testid={`merchant-detail-${merchantId}`}>
      <section>
        <h4 className="text-xs font-semibold text-muted uppercase tracking-wide mb-2">Learned categories</h4>
        {detail.isLoading && <p className="text-sm text-muted">Loading…</p>}
        <LoadError error={detail.error} what="the learned categories" />
        {detail.data && detail.data.distribution.length === 0 && (
          <p className="text-sm text-muted">Nothing learned for this merchant yet.</p>
        )}
        {detail.data && detail.data.distribution.length > 0 && (
          <table className="text-sm">
            <thead>
              <tr className="text-xs text-muted text-left">
                <th className="pr-6 font-medium">Category</th>
                <th className="pr-6 font-medium">Confirmations</th>
                <th className="font-medium">Confidence</th>
              </tr>
            </thead>
            <tbody>
              {detail.data.distribution.map((d) => (
                <tr key={d.category}>
                  <td className="pr-6 text-ink">{d.category}</td>
                  <td className="pr-6 font-mono">{d.confirmationCount}</td>
                  <td className="font-mono">{d.confidence}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section>
        <h4 className="text-xs font-semibold text-muted uppercase tracking-wide mb-2">Learning history</h4>
        {history.isLoading && <p className="text-sm text-muted">Loading…</p>}
        <LoadError error={history.error} what="the learning history" />
        {history.data && history.data.length === 0 && <p className="text-sm text-muted">No learning events yet.</p>}
        {history.data && history.data.length > 0 && (
          <ul className="space-y-1 text-sm">
            {history.data.map((e, i) => (
              <li key={`${e.createdAt}-${i}`} className="flex flex-wrap gap-x-3">
                <span className="text-muted text-xs font-mono">{formatIst(e.createdAt)}</span>
                <span className="text-ink">{actionLabel(e.action)}</span>
                {(e.previousCategory || e.newCategory) && (
                  <span className="text-muted">{e.previousCategory ?? '—'} → {e.newCategory ?? '—'}</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h4 className="text-xs font-semibold text-muted uppercase tracking-wide mb-2">
          Transactions{transactions.data ? ` (${txns.length})` : ''}
        </h4>
        {transactions.isLoading && <p className="text-sm text-muted">Loading…</p>}
        <LoadError error={transactions.error} what="this merchant's transactions" />
        <LoadError error={canManage ? categories.error : null} what="the customer's categories" />
        {transactions.data && txns.length === 0 && <p className="text-sm text-muted">No transactions are attributed to this merchant.</p>}
        <ul className="divide-y divide-border">
          {shown.map((t) => (
            <li key={t.id} className="py-2 flex flex-wrap items-center justify-between gap-2 text-sm">
              <div className="min-w-0">
                <p className="text-ink truncate">{t.description}</p>
                <p className="text-xs text-muted">
                  {t.date} · {t.categoryName ?? 'Uncategorized'}
                  {t.needsCategoryReview && <span className="ml-1 text-warning">· needs review</span>}
                </p>
              </div>
              <div className="flex items-center gap-2 flex-shrink-0">
                <span className={`font-mono ${t.type === 'EXPENSE' ? 'text-danger' : 'text-success'}`}>{rupees(t)}</span>
                {canManage && categories.data && (
                  <>
                    <select
                      aria-label={`Category for ${t.description}`}
                      value={chosen[t.id] ?? ''}
                      onChange={(e) => setChosen((c) => ({ ...c, [t.id]: e.target.value }))}
                      className="bg-card border border-border rounded-lg px-2 py-1 text-xs max-w-[10rem]"
                    >
                      <option value="">Set category…</option>
                      {categories.data.filter((c) => c.id !== t.categoryId).map((c) => (
                        <option key={c.id} value={c.id}>{c.name}</option>
                      ))}
                    </select>
                    <button
                      type="button"
                      disabled={!chosen[t.id] || confirmMutation.isPending}
                      onClick={() => setConfirming({ txn: t, categoryId: chosen[t.id] })}
                      className="text-xs font-semibold text-primary px-2 py-1 disabled:opacity-40"
                    >
                      Apply
                    </button>
                  </>
                )}
              </div>
            </li>
          ))}
        </ul>
        {txns.length > FIRST_TRANSACTIONS && (
          <button type="button" onClick={() => setShowAll((s) => !s)} className="mt-2 text-xs font-medium text-primary hover:underline">
            {showAll ? `Show the first ${FIRST_TRANSACTIONS}` : `Show all ${txns.length}`}
          </button>
        )}
      </section>

      {confirming && (
        <ConfirmDialog
          title={`Set this transaction to ${categoryName(confirming.categoryId)}?`}
          message={`"${confirming.txn.description}" (${rupees(confirming.txn)}) changes in the customer's account, and `
            + `${merchantName} learns ${categoryName(confirming.categoryId)} as one of its categories. Undo on the `
            + 'merchant rolls back only the learning, not the transaction.'}
          confirmLabel="Set category"
          busy={confirmMutation.isPending}
          onConfirm={() => confirmMutation.mutate(confirming)}
          onCancel={() => setConfirming(null)}
        />
      )}
    </div>
  );
}
