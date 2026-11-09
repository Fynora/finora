import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ShieldAlert } from 'lucide-react';
import { suspensionApi, type SuspensionRequest, type SuspensionStatus } from '../api/endpoints';
import { ExportDataModal } from './ExportDataModal';

const OPEN = new Set<SuspensionRequest['status']>(['OPEN', 'IN_PROGRESS']);
const MAX_TEXT = 4000;

function openRequest(status: SuspensionStatus | undefined, type: SuspensionRequest['type']) {
  return status?.requests.find((r) => r.type === type && OPEN.has(r.status)) ?? null;
}

function formatDate(iso: string | null) {
  if (!iso) return null;
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' });
}

/**
 * Privacy workstream 10. Shown in place of the sign-in form when the backend refuses a suspended
 * customer's sign-in (AUTH_ACCOUNT_SUSPENDED). `token` is the short-lived token that refusal
 * carried; it reaches only what a suspended account may still do: read why, appeal, ask for
 * deletion, and download their data. It is never stored as a session.
 */
export function SuspendedAccountScreen({ token, onClose }: { token: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const queryKey = ['suspension-status', token];
  const statusQ = useQuery({ queryKey, queryFn: () => suspensionApi.status(token) });
  const [appealText, setAppealText] = useState('');
  const [confirmDeletion, setConfirmDeletion] = useState(false);
  const [showExport, setShowExport] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const requestMutation = useMutation({
    mutationFn: ({ type, text }: { type: 'APPEAL' | 'DELETION'; text?: string }) =>
      suspensionApi.raiseRequest(token, type, text),
    onSuccess: (status) => {
      queryClient.setQueryData(queryKey, status);
      setAppealText('');
      setConfirmDeletion(false);
      setError(null);
    },
    onError: (err: any) => setError(err.response?.data?.message ?? 'Could not send that. Please try again.'),
  });

  const status = statusQ.data;
  const appeal = openRequest(status, 'APPEAL');
  const deletion = openRequest(status, 'DELETION');
  const since = formatDate(status?.suspendedAt ?? null);

  return (
    <div className="bg-card rounded-xl2 p-8 w-full shadow-soft border border-border" data-testid="suspended-account-screen">
      <div className="flex items-center gap-2 mb-1">
        <ShieldAlert size={20} className="text-danger" aria-hidden="true" />
        <h2 className="text-2xl font-bold text-ink">Your account is suspended</h2>
      </div>

      {statusQ.isLoading && <p className="text-sm text-muted mt-4">Loading…</p>}
      {statusQ.isError && !status && (
        <p className="text-sm text-danger mt-4">
          We couldn't load the details. Sign in again to see them.
        </p>
      )}

      {status && (
        <>
          <p className="text-sm text-muted mb-4">
            {since ? `Since ${since}, you can't use Fynora.` : "You can't use Fynora while your account is suspended."}
          </p>
          <div className="rounded-lg bg-danger-bg p-3 mb-4">
            <p className="text-sm text-ink font-semibold">Why</p>
            <p className="text-sm text-ink">{status.reasonText}</p>
            {status.message && <p className="text-sm text-ink mt-2">Message from Fynora: {status.message}</p>}
          </div>

          {status.storePlatform && (
            <p className="rounded-lg bg-warning-bg p-3 mb-4 text-xs text-ink">
              Your Plus subscription is paid through {status.storePlatform === 'IOS' ? 'the App Store' : 'Google Play'}.
              Fynora can't stop it, so cancel it there if you don't want to be charged again.
            </p>
          )}

          {error && <p className="text-danger text-sm mb-4" role="alert">{error}</p>}

          <section className="mb-5" aria-labelledby="appeal-heading">
            <h3 id="appeal-heading" className="text-sm font-semibold text-ink mb-1">Appeal</h3>
            {appeal ? (
              <p className="text-sm text-muted">
                Your appeal ({appeal.ticketNumber}) is with us. We'll email you when we've reviewed it.
              </p>
            ) : (
              <>
                <label htmlFor="appeal-text" className="text-xs text-muted block mb-1">
                  Tell us why you think this is a mistake.
                </label>
                <textarea
                  id="appeal-text"
                  rows={4}
                  maxLength={MAX_TEXT}
                  value={appealText}
                  onChange={(e) => setAppealText(e.target.value)}
                  className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
                />
                <button
                  type="button"
                  disabled={appealText.trim() === '' || requestMutation.isPending}
                  onClick={() => requestMutation.mutate({ type: 'APPEAL', text: appealText.trim() })}
                  className="mt-2 w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
                >
                  {requestMutation.isPending ? 'Sending…' : 'Send appeal'}
                </button>
              </>
            )}
          </section>

          <section className="mb-5" aria-labelledby="data-heading">
            <h3 id="data-heading" className="text-sm font-semibold text-ink mb-1">Your data</h3>
            <button
              type="button"
              onClick={() => setShowExport(true)}
              className="w-full border border-border rounded-lg py-2.5 text-sm font-semibold text-ink hover:bg-bg"
            >
              Download my data
            </button>
          </section>

          <section className="mb-5" aria-labelledby="delete-heading">
            <h3 id="delete-heading" className="text-sm font-semibold text-ink mb-1">Delete your account</h3>
            {deletion ? (
              <p className="text-sm text-muted">
                Your deletion request ({deletion.ticketNumber}) is with us. We'll email you when it's done.
              </p>
            ) : confirmDeletion ? (
              <>
                <p className="text-sm text-muted mb-2">
                  We'll delete your account and its data once we've checked nothing has to be kept by law.
                  Download your data first if you want a copy.
                </p>
                <button
                  type="button"
                  disabled={requestMutation.isPending}
                  onClick={() => requestMutation.mutate({ type: 'DELETION' })}
                  className="w-full bg-danger text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
                >
                  Send deletion request
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmDeletion(false)}
                  className="w-full mt-1 text-xs text-muted hover:text-ink py-1.5"
                >
                  Cancel
                </button>
              </>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmDeletion(true)}
                className="w-full text-sm text-danger hover:underline py-1.5"
              >
                Ask us to delete my account
              </button>
            )}
          </section>
        </>
      )}

      <button
        type="button"
        onClick={onClose}
        className="w-full text-xs text-muted hover:text-ink py-1.5"
      >
        Back to sign in
      </button>

      {showExport && status && (
        <ExportDataModal
          signInMethod={status.signInMethod}
          onClose={() => setShowExport(false)}
          exportData={(password, googleIdToken, appleIdToken) =>
            suspensionApi.exportData(token, password, googleIdToken, appleIdToken)}
        />
      )}
    </div>
  );
}
