import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { referralsApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { useDialogA11y } from '../design-system';

/**
 * "Have a referral code?" -- asked once, right after a Google/Apple sign-up. Those buttons create
 * the account without the email form's code field, so a friend's referral was lost unless it came
 * through a ?ref= link. AuthContext.referralPromptPending decides WHEN (a new Google/Apple account,
 * no code sent); canApplyCode from the server decides WHETHER -- false once the account has a
 * referral or has subscribed, and then there is nothing to ask. Mirrors mobile's ReferralCodePrompt.
 *
 * Adding a code or skipping clears the prompt for good. Skipping is not a loss: the Referrals page
 * keeps an "Enter a friend's code" box while canApplyCode holds.
 */
export function ReferralCodePrompt() {
  const { referralPromptPending, dismissReferralPrompt } = useAuth();
  // Nothing pending (every session but a fresh Google/Apple sign-up): no query, no request.
  if (!referralPromptPending) return null;
  return <PendingPrompt onDone={dismissReferralPrompt} />;
}

function PendingPrompt({ onDone }: { onDone: () => void }) {
  const { data } = useQuery({
    queryKey: ['referrals-mine'],
    queryFn: () => referralsApi.mine(),
  });

  // Already referred or already subscribed: nothing to offer, so the pending flag is spent.
  useEffect(() => {
    if (data && !data.canApplyCode) onDone();
  }, [data, onDone]);

  if (!data?.canApplyCode) return null;
  return <PromptDialog onDone={onDone} />;
}

function PromptDialog({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient();
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const apply = useMutation({
    mutationFn: (value: string) => referralsApi.applyCode(value),
    onMutate: () => setError(null),
    onSuccess: () => {
      onDone();
      void queryClient.invalidateQueries({ queryKey: ['referrals-mine'] });
    },
    onError: (e: any) => setError(e?.response?.data?.message ?? 'Could not add this code. Try again.'),
  });
  const submitting = apply.isPending;
  // No closing while the request is in flight, so it can't look "skipped" while the code is added.
  const panelRef = useDialogA11y({ onClose: onDone, closeDisabled: submitting });
  const trimmed = code.trim();
  const canSubmit = trimmed.length > 0 && !submitting;

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-30" onClick={submitting ? undefined : onDone}>
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="referral-code-prompt-title"
        tabIndex={-1}
        className="bg-card rounded-xl2 shadow-card p-6 w-[420px] max-w-[90vw]"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="referral-code-prompt-title" className="text-lg font-semibold text-ink">Have a referral code?</h2>
        <p className="text-sm text-muted mt-1">
          If a friend invited you, enter their code so it counts for them. You can only use one code.
        </p>
        <form
          className="mt-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (canSubmit) apply.mutate(trimmed);
          }}
        >
          <label htmlFor="referral-code-prompt-input" className="sr-only">Referral code</label>
          <input
            id="referral-code-prompt-input"
            value={code}
            onChange={(e) => setCode(e.target.value.toUpperCase())}
            autoComplete="off"
            placeholder="Referral code"
            className="w-full rounded-lg border border-border bg-bg px-3 py-2 text-ink tracking-wider"
          />
          {error && <p className="text-xs text-danger mt-2" role="alert">{error}</p>}
          <div className="flex gap-2 mt-4">
            <button
              type="button"
              onClick={onDone}
              disabled={submitting}
              className="flex-1 text-sm font-semibold px-4 py-2 rounded-lg border border-border text-muted disabled:opacity-50"
            >
              Skip
            </button>
            <button
              type="submit"
              disabled={!canSubmit}
              className="flex-1 text-sm font-semibold px-4 py-2 rounded-lg bg-primary text-on-primary disabled:opacity-50"
            >
              {submitting ? 'Adding…' : 'Add code'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
