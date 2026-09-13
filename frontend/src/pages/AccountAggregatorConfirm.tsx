import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { accountAggregatorApi, accountsApi } from '../api/endpoints';
import type { Account } from '../types';

// Task 9 (Plan 5). AccountAggregatorLinkController.confirmExistingAccount/confirmNewAccount have
// existed since Plan 1 with zero frontend callers -- this is the first UI for them, not new
// backend behavior.
//
// Not the design spec's originally-suggested "Is this your [Bank] account ending ****1234?" --
// that copy assumes a specific suggested candidate is available to show. It is not: traced
// AccountAggregatorIdentityResolutionService.resolveAndAttach's PROBABLE branch directly (see its
// own comment, "the candidate(s) stay available via match.candidates() for the confirmation
// endpoints to offer") and confirmed match.candidates() is a local variable, never persisted onto
// the link or exposed by any endpoint -- confirmExistingAccount only ever took an accountId
// supplied by the caller, with no endpoint anywhere returning what that id should be. Rather than
// inventing a candidate the backend cannot actually back up, this screen instead offers a picker
// over the user's own existing accounts (accountsApi.list(), already real and available) -- the
// user identifies the match themselves, same as they already do for a PROBABLE match during
// manual CSV/PDF import.
export default function AccountAggregatorConfirm() {
  const { linkId } = useParams<{ linkId: string }>();
  const navigate = useNavigate();

  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [selectedAccountId, setSelectedAccountId] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    accountsApi.list()
      .then((list) => {
        setAccounts(list);
        if (list.length > 0) setSelectedAccountId(list[0].id);
      })
      .catch(() => setLoadError(true))
      .finally(() => setLoading(false));
  }, []);

  async function handleConfirmExisting() {
    if (!linkId || !selectedAccountId) return;
    setBusy(true);
    setActionError(null);
    try {
      await accountAggregatorApi.confirmExistingAccount(linkId, selectedAccountId);
      navigate('/app/settings');
    } catch (err) {
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setActionError(message || "Couldn't confirm this account -- please try again.");
      setBusy(false);
    }
  }

  async function handleConfirmNew() {
    if (!linkId) return;
    setBusy(true);
    setActionError(null);
    try {
      await accountAggregatorApi.confirmNewAccount(linkId);
      navigate('/app/settings');
    } catch (err) {
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setActionError(message || "Couldn't set this up as a new account -- please try again.");
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6 max-w-lg">
      <div>
        <button
          type="button"
          onClick={() => navigate('/app/settings')}
          className="text-xs text-muted hover:text-ink inline-flex items-center gap-1 mb-3"
        >
          <ArrowLeft size={13} /> Settings
        </button>
        <h1 className="text-2xl font-semibold text-ink">Confirm Your Bank Account</h1>
        <p className="text-sm text-muted mt-1">
          We connected a bank account through Account Aggregator, but couldn't automatically match
          it to one of your existing accounts. Is this an account you already track in Fynora?
        </p>
      </div>

      {loading ? (
        <p className="text-muted text-sm">Loading your accounts…</p>
      ) : loadError ? (
        <p className="text-sm text-danger">Couldn't load your accounts — please try again later.</p>
      ) : (
        <div className="bg-card border border-border rounded-lg p-4 space-y-4">
          {accounts.length > 0 && (
            <div>
              <label htmlFor="aa-confirm-account" className="block text-2xs uppercase text-muted mb-1">
                Which of your accounts is this?
              </label>
              <select
                id="aa-confirm-account"
                value={selectedAccountId}
                onChange={(e) => setSelectedAccountId(e.target.value)}
                className="bg-bg text-ink border border-border rounded-lg px-3 py-1.5 text-sm w-full"
              >
                {accounts.map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.name} — {account.bank.shortName}
                    {account.accountNumberMasked ? ` ${account.accountNumberMasked}` : ''}
                  </option>
                ))}
              </select>
            </div>
          )}

          {actionError && <p className="text-xs text-danger">{actionError}</p>}

          <div className="flex flex-col gap-2 pt-2 border-t border-border">
            {accounts.length > 0 && (
              <button
                type="button"
                disabled={busy}
                onClick={handleConfirmExisting}
                className="bg-primary text-on-primary hover:bg-primary-dark disabled:opacity-50 rounded-lg px-3 py-2 text-sm uppercase font-medium"
              >
                Yes, this is my account
              </button>
            )}
            <button
              type="button"
              disabled={busy}
              onClick={handleConfirmNew}
              className="border border-border rounded-lg px-3 py-2 text-sm uppercase font-medium text-ink hover:bg-black/5 disabled:opacity-50"
            >
              This is a different/new account
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
