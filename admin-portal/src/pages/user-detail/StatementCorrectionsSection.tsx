import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminStatementCorrectionsApi, type AppliedCorrection, type WaitingCorrection } from '../../api/endpoints';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { errorMessage } from './errorMessage';

const MIN_REASON = 15;
const MAX_REASON = 500;

/**
 * Statement corrections the app has checked and is waiting for this user to apply (the "Update"
 * banner in their app), which an admin can apply for them. Each apply runs the same refresh as the
 * user's own button, needs a typed reason that is audited, and tells the user by push and email.
 */
export function StatementCorrectionsSection({ userId }: { userId: string }) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<WaitingCorrection | null>(null);
  const [reason, setReason] = useState('');
  const [result, setResult] = useState<AppliedCorrection | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin-statement-corrections', userId],
    queryFn: () => adminStatementCorrectionsApi.waiting(userId),
  });

  const apply = useMutation({
    mutationFn: (c: { statementId: string; reason: string }) =>
      adminStatementCorrectionsApi.apply(c.statementId, c.reason),
    onSuccess: (applied) => {
      setResult(applied);
      setSelected(null);
      setReason('');
      void queryClient.invalidateQueries({ queryKey: ['admin-statement-corrections', userId] });
    },
  });

  const trimmed = reason.trim();
  const reasonOk = trimmed.length >= MIN_REASON && trimmed.length <= MAX_REASON;

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <h3 className="text-sm font-semibold text-ink mb-1">Statement corrections</h3>
      <p className="text-xs text-muted mb-3">
        Corrections the app has checked and is waiting for this user to apply. Applying one here runs the same
        update as their own button, is recorded with your reason, and the user is told by push and email.
      </p>

      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {error && <p className="text-sm text-danger">Failed to load statement corrections.</p>}
      {data && data.length === 0 && <p className="text-sm text-muted">No correction is waiting for this user.</p>}

      {data && data.length > 0 && (
        <ul className="space-y-2">
          {data.map((c) => (
            <li key={c.statementImportId}
                className="flex items-center justify-between gap-3 text-sm py-2 border-b border-border last:border-b-0">
              <div className="min-w-0">
                <p className="text-ink font-medium truncate">{c.accountName ?? 'Account'} · {c.fileName ?? 'statement'}</p>
                <p className="text-xs text-muted">
                  {c.periodStart && c.periodEnd ? `${c.periodStart} to ${c.periodEnd} · ` : ''}
                  {c.rowsChanged} changed · {c.rowsAdded} added · {c.rowsRemoved} removed
                  {c.factsChanged > 0 ? ` · ${c.factsChanged} statement detail(s)` : ''}
                </p>
              </div>
              <button
                type="button"
                disabled={apply.isPending}
                onClick={() => { setResult(null); apply.reset(); setSelected(c); }}
                className="shrink-0 bg-primary hover:bg-primary-dark text-on-primary text-xs font-semibold rounded-lg px-3 py-1.5 disabled:opacity-50"
              >
                Apply correction
              </button>
            </li>
          ))}
        </ul>
      )}

      {result && (
        <p role="status" className="text-sm mt-3 text-ink">
          {result.status === 'APPLIED'
            ? `Applied: ${result.rowsChanged} changed, ${result.rowsAdded} added, ${result.rowsRemoved} removed.`
              + (result.userNotified ? ' The user has been told.' : ' The notice to the user could not be queued.')
            : `Not applied (${result.status}${result.reason ? `: ${result.reason}` : ''}). Nothing in the account changed.`}
        </p>
      )}

      {selected && (
        <ConfirmDialog
          title="Apply this correction for the user?"
          message={`${selected.rowsChanged} changed, ${selected.rowsAdded} added and ${selected.rowsRemoved} removed `
            + 'transactions on this statement. It changes their account the same way their own Update button would, '
            + 'and they are told by push and email.'}
          confirmLabel="Apply correction"
          busy={apply.isPending}
          confirmDisabled={!reasonOk}
          onConfirm={() => apply.mutate({ statementId: selected.statementImportId, reason: trimmed })}
          onCancel={() => { setSelected(null); setReason(''); }}
        >
          <label className="block text-xs font-semibold text-ink mt-3 mb-1" htmlFor="correction-reason">Reason</label>
          <textarea
            id="correction-reason"
            value={reason}
            maxLength={MAX_REASON}
            onChange={(e) => setReason(e.target.value)}
            placeholder="e.g. Card payments were stored as spends by an older parser"
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
            rows={3}
          />
          <p className="text-xs text-muted mt-1">{trimmed.length} / {MAX_REASON} · at least {MIN_REASON} characters, recorded in the audit log</p>
          {apply.isError && (
            // Inside the dialog, which stays open on a failure, so the admin reads why.
            <p role="alert" className="text-sm mt-2 text-danger">
              {errorMessage(apply.error, 'The correction could not be applied.')}
            </p>
          )}
        </ConfirmDialog>
      )}
    </div>
  );
}
