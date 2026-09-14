import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { accountAggregatorApi, type AccountAggregatorLinkDto } from '../../api/endpoints';
import { Skeleton } from '../../design-system/Skeleton';
import { Button } from '../../design-system/Button';
import { FinoraCard } from '../../design-system/FinoraCard';
import { SectionHeader } from '../../design-system/SectionHeader';

const AA_FI_TYPE_LABELS: Record<string, string> = {
  DEPOSIT: 'Bank Account',
  CREDIT_CARD: 'Credit Card',
};
function aaFiTypeLabel(fiType: string): string {
  return AA_FI_TYPE_LABELS[fiType] ?? fiType;
}

/** Per-status copy for a linked AA account row. PAUSED is deliberately NOT a manual reconnect
 *  button here -- AccountAggregatorLinkLifecycleSweepService resumes it automatically once
 *  entitlement is regained. */
function aaStatusCopy(status: string): string {
  switch (status) {
    case 'ACTIVE': return 'Connected';
    case 'PAUSED': return "Your plan no longer includes Bank Sync -- reconnects automatically once you upgrade";
    case 'CONSENT_PENDING': return 'Waiting for you to approve this in your banking app';
    case 'PENDING_ACCOUNT_CONFIRMATION': return 'Waiting for you to confirm which account this is';
    case 'REJECTED': return 'Declined -- try again';
    case 'LINK_FAILED': return "Couldn't connect -- try again";
    case 'EXPIRED': return 'Your bank connection has expired -- reconnect';
    case 'REVOKED': return 'Disconnected';
    default: return status;
  }
}

function aaStatusBadgeClass(status: string): string {
  switch (status) {
    case 'ACTIVE': return 'text-success bg-success-bg';
    case 'PAUSED':
    case 'EXPIRED': return 'text-warning bg-warning-bg';
    case 'REJECTED':
    case 'LINK_FAILED': return 'text-danger bg-danger-bg';
    default: return 'text-muted';
  }
}

// Terminal: REVOKED cannot be un-revoked, EXPIRED/REJECTED/LINK_FAILED all need a brand-new
// consent flow, not a disconnect of this one.
const AA_TERMINAL_STATUSES = new Set(['REVOKED', 'EXPIRED', 'REJECTED', 'LINK_FAILED']);

export function BankSyncPane() {
  const navigate = useNavigate();
  const [aaLinks, setAaLinks] = useState<AccountAggregatorLinkDto[]>([]);
  const [aaLoading, setAaLoading] = useState(true);
  const [aaError, setAaError] = useState(false);
  const [aaConnecting, setAaConnecting] = useState(false);
  const [aaActionError, setAaActionError] = useState<string | null>(null);
  const [aaDisconnectingId, setAaDisconnectingId] = useState<string | null>(null);
  const [aaConfirmingDisconnectId, setAaConfirmingDisconnectId] = useState<string | null>(null);

  function loadAaLinks() {
    setAaLoading(true);
    setAaError(false);
    accountAggregatorApi.list()
      .then(setAaLinks)
      .catch(() => setAaError(true))
      .finally(() => setAaLoading(false));
  }

  useEffect(() => {
    loadAaLinks();
  }, []);

  async function handleAaConnect() {
    setAaConnecting(true);
    setAaActionError(null);
    try {
      const { redirectUrl } = await accountAggregatorApi.initiate('DEPOSIT', crypto.randomUUID());
      if (redirectUrl) {
        window.location.href = redirectUrl;
      } else {
        setAaActionError('This connection attempt is already in progress.');
        setAaConnecting(false);
      }
    } catch (err) {
      setAaConnecting(false);
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setAaActionError(message || "Couldn't start connecting your bank -- please try again.");
    }
  }

  async function handleAaDisconnect(linkId: string) {
    setAaDisconnectingId(linkId);
    setAaActionError(null);
    try {
      await accountAggregatorApi.disconnect(linkId);
      setAaConfirmingDisconnectId(null);
      loadAaLinks();
    } catch (err) {
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setAaActionError(message || "Couldn't disconnect -- please try again.");
    } finally {
      setAaDisconnectingId(null);
    }
  }

  return (
    <FinoraCard>
      <SectionHeader title="Bank Sync" />
      <p className="text-sm text-muted -mt-3 mb-5">Automatically sync transactions from your linked bank accounts</p>
      {aaLoading ? (
        <Skeleton.Region label="Loading your linked bank accounts">
          <AaSkeletonFields />
        </Skeleton.Region>
      ) : aaError ? (
        <p className="text-xs text-danger">Couldn't load your linked bank accounts — please try again later.</p>
      ) : (
        <div>
          {aaLinks.length === 0 ? (
            <p className="text-2xs text-muted mb-3">No bank accounts linked yet.</p>
          ) : (
            <div className="space-y-3 mb-3">
              {aaLinks.map((link) => (
                <div key={link.id} className="border border-border rounded-lg px-3 py-2.5">
                  <div className="flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <p className="text-sm text-ink font-medium flex items-center gap-2">
                        {aaFiTypeLabel(link.fiType)}
                        <span className={`text-2xs font-medium uppercase tracking-wide rounded px-1.5 py-0.5 ${aaStatusBadgeClass(link.status)}`}>
                          {link.status.replace(/_/g, ' ')}
                        </span>
                      </p>
                      <p className="text-2xs text-muted mt-1">{aaStatusCopy(link.status)}</p>
                    </div>
                    <div className="flex flex-col gap-1.5 flex-shrink-0">
                      {link.status === 'PENDING_ACCOUNT_CONFIRMATION' && (
                        <Button size="sm" onClick={() => navigate(`/app/settings/bank-sync/${link.id}/confirm`)}>
                          Confirm Account
                        </Button>
                      )}
                      {!AA_TERMINAL_STATUSES.has(link.status) && (
                        <Button variant="secondary" size="sm" onClick={() => setAaConfirmingDisconnectId(link.id)}>
                          Disconnect
                        </Button>
                      )}
                    </div>
                  </div>
                  {aaConfirmingDisconnectId === link.id && (
                    <div className="mt-3 pt-3 border-t border-border">
                      <p className="text-2xs text-muted">
                        This does not cancel your consent at your banking app -- it only stops Fynora
                        from syncing this account. To fully revoke access, do that from your AA app.
                      </p>
                      <div className="flex items-center gap-2 mt-2">
                        <Button
                          variant="danger"
                          size="sm"
                          loading={aaDisconnectingId === link.id}
                          onClick={() => handleAaDisconnect(link.id)}
                        >
                          Confirm Disconnect
                        </Button>
                        <Button variant="secondary" size="sm" onClick={() => setAaConfirmingDisconnectId(null)}>
                          Cancel
                        </Button>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
          <Button size="sm" loading={aaConnecting} onClick={handleAaConnect}>
            Connect a Bank Account
          </Button>
          {aaActionError && <p className="text-xs text-danger mt-2">{aaActionError}</p>}
        </div>
      )}
    </FinoraCard>
  );
}

function AaSkeletonFields() {
  return (
    <div className="flex items-center justify-between gap-3">
      <Skeleton.Text width="w-64" className="h-2.5" />
      <Skeleton.Block className="h-7 w-40 flex-shrink-0" />
    </div>
  );
}
