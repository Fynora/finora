import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { RefreshCw, Crown } from 'lucide-react';
import { gmailApi, type GmailConnectionStatus } from '../../api/endpoints';
import { PremiumFeatureGate } from '../../components/PremiumFeatureGate';
import { formatRelativeTime, MetricTile } from '../../components/AccountUI';
import { Skeleton } from '../../design-system/Skeleton';
import { Button } from '../../design-system/Button';
import { IconButton } from '../../design-system/IconButton';
import { FinoraCard } from '../../design-system/FinoraCard';
import { SectionHeader } from '../../design-system/SectionHeader';

/** One-shot message for the `?gmail=` query param GoogleOAuthController's callback redirect
 *  lands with -- the outcome travels as a query parameter specifically so no token or error
 *  detail can end up in it. Null for anything else (including no param at all). */
function gmailCallbackMessage(gmail: string | null): { text: string; isError: boolean } | null {
  switch (gmail) {
    case 'connected': return { text: 'Gmail connected.', isError: false };
    case 'declined': return { text: 'Gmail connection was cancelled.', isError: false };
    case 'invalid':
    case 'failed':
      return { text: "Couldn't connect Gmail -- please try again.", isError: true };
    default: return null;
  }
}

function gmailLastSyncedLabel(status: GmailConnectionStatus): string {
  const label = formatRelativeTime(status.lastDiscoveryAt);
  return label ? `Last synced ${label}` : 'Never synced yet';
}

const SCOPE_LABELS: Record<string, string> = {
  'https://www.googleapis.com/auth/gmail.readonly': 'Read Gmail messages',
  'https://www.googleapis.com/auth/userinfo.email': 'See your email address',
};
function gmailPermissionLabels(scopes: string[]): string[] {
  return scopes.map((s) => SCOPE_LABELS[s]).filter((label): label is string => !!label);
}

export function ConnectedAppsPane() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const [gmailStatus, setGmailStatus] = useState<GmailConnectionStatus | null>(null);
  const [gmailLoading, setGmailLoading] = useState(true);
  const [gmailError, setGmailError] = useState(false);
  const [gmailConnecting, setGmailConnecting] = useState(false);
  const [gmailSyncing, setGmailSyncing] = useState(false);
  const [gmailSyncError, setGmailSyncError] = useState<string | null>(null);
  const [gmailActionError, setGmailActionError] = useState<string | null>(null);
  const [gmailDisconnecting, setGmailDisconnecting] = useState(false);
  const [gmailCallbackNotice] = useState(() => gmailCallbackMessage(searchParams.get('gmail')));

  function loadGmailStatus() {
    setGmailLoading(true);
    setGmailError(false);
    gmailApi.status()
      .then(setGmailStatus)
      .catch(() => setGmailError(true))
      .finally(() => setGmailLoading(false));
  }

  useEffect(() => {
    loadGmailStatus();
    // Strip ?gmail=... from the URL once read (gmailCallbackNotice's initializer already
    // captured it) so a page refresh doesn't replay a stale "Gmail connected" message. Copies
    // searchParams first and deletes only this one key, so the shell's own ?tab= survives.
    if (searchParams.has('gmail')) {
      const next = new URLSearchParams(searchParams);
      next.delete('gmail');
      setSearchParams(next, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleGmailConnect() {
    setGmailConnecting(true);
    setGmailActionError(null);
    try {
      const { authorizationUrl } = await gmailApi.connect();
      window.location.href = authorizationUrl;
    } catch (err) {
      setGmailConnecting(false);
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setGmailActionError(message || "Couldn't start the Gmail connection -- please try again.");
    }
  }

  async function handleGmailSyncNow() {
    setGmailSyncing(true);
    setGmailSyncError(null);
    try {
      await gmailApi.syncNow();
      loadGmailStatus();
    } catch (err) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      setGmailSyncError(
        status === 429 ? 'Gmail was synced recently -- try again in a moment.'
          : status === 409 ? 'This connection needs to be reconnected -- disconnect and connect again.'
          : "Gmail sync didn't complete -- try again in a moment.");
    } finally {
      setGmailSyncing(false);
    }
  }

  async function handleGmailDisconnect() {
    setGmailDisconnecting(true);
    setGmailActionError(null);
    try {
      await gmailApi.disconnect();
      loadGmailStatus();
    } catch {
      setGmailActionError("Couldn't disconnect Gmail -- please try again.");
    } finally {
      setGmailDisconnecting(false);
    }
  }

  return (
    <FinoraCard>
      <SectionHeader title="Connected Apps" />
      <p className="text-sm text-muted -mt-3 mb-5">Link external accounts Fynora can read transactions from</p>
      {gmailCallbackNotice && (
        <p className={`text-xs mb-3 ${gmailCallbackNotice.isError ? 'text-danger' : 'text-success'}`}>
          {gmailCallbackNotice.text}
        </p>
      )}
      {gmailLoading ? (
        <Skeleton.Region label="Loading your Gmail connection">
          <GmailSkeletonFields />
        </Skeleton.Region>
      ) : gmailError && !gmailStatus ? (
        <p className="text-xs text-danger">Couldn't load your Gmail connection — please try again later.</p>
      ) : !gmailStatus?.available ? (
        <p className="text-xs text-muted italic">Gmail sync isn't available on this deployment yet.</p>
      ) : !gmailStatus.connected && gmailStatus.needsReconnect ? (
        <PremiumFeatureGate featureKey="GMAIL_SYNC" fallback={<GmailUpgradePrompt />}>
          <div className="border border-warning/40 rounded-lg px-3 py-2.5">
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="text-sm text-ink font-medium flex items-center gap-2">
                  Gmail
                  <span className="text-2xs font-medium uppercase tracking-wide text-warning bg-warning-bg rounded px-1.5 py-0.5">
                    Needs reconnect
                  </span>
                </p>
                {gmailStatus.googleEmail && (
                  <p className="text-2xs text-muted truncate mt-0.5">{gmailStatus.googleEmail}</p>
                )}
                <p className="text-2xs text-muted mt-1">
                  Google stopped accepting this connection -- reconnect to keep finding receipts.
                </p>
              </div>
              <Button size="sm" className="flex-shrink-0" loading={gmailConnecting} onClick={handleGmailConnect}>
                Reconnect Gmail
              </Button>
            </div>
            {gmailActionError && <p className="text-xs text-danger mt-2">{gmailActionError}</p>}
          </div>
        </PremiumFeatureGate>
      ) : !gmailStatus.connected ? (
        <PremiumFeatureGate featureKey="GMAIL_SYNC" fallback={<GmailUpgradePrompt />}>
          <div>
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-sm text-ink font-medium">Gmail</p>
                <p className="text-2xs text-muted mt-0.5">
                  Automatically detect receipts from your inbox — nothing is imported without your review.
                </p>
              </div>
              <Button size="sm" className="flex-shrink-0" loading={gmailConnecting} onClick={handleGmailConnect}>
                Connect Gmail
              </Button>
            </div>
            {gmailActionError && <p className="text-xs text-danger mt-2">{gmailActionError}</p>}
          </div>
        </PremiumFeatureGate>
      ) : (
        <div className="border border-border rounded-lg px-3 py-2.5">
          <div className="flex items-center justify-between gap-3">
            <div className="min-w-0">
              <p className="text-sm text-ink font-medium flex items-center gap-2">
                Gmail
                <span className="text-2xs font-medium uppercase tracking-wide text-success bg-success-bg rounded px-1.5 py-0.5">
                  Connected
                </span>
              </p>
              <p className="text-2xs text-muted truncate mt-0.5">{gmailStatus.googleEmail}</p>
              <p className="text-2xs text-muted mt-1">{gmailLastSyncedLabel(gmailStatus)}</p>
              {gmailPermissionLabels(gmailStatus.grantedScopes).length > 0 && (
                <p className="text-2xs text-muted mt-1">
                  <span className="text-ink">Permissions:</span> {gmailPermissionLabels(gmailStatus.grantedScopes).join(', ')}
                  {' — never sent, modified, or deleted'}
                </p>
              )}
            </div>
            <IconButton
              size="sm"
              className="flex-shrink-0"
              icon={<RefreshCw size={14} />}
              aria-label="Sync Gmail now"
              title="Sync now"
              loading={gmailSyncing}
              onClick={handleGmailSyncNow}
            />
          </div>

          <div className="grid grid-cols-2 gap-3 mt-3">
            <MetricTile label="Transactions Found" value={gmailStatus.transactionsFound.toLocaleString('en-IN')} />
            <MetricTile label="Needs Review" value={gmailStatus.needsReview.toLocaleString('en-IN')} />
          </div>

          {gmailSyncError && <p className="text-xs text-danger mt-2">{gmailSyncError}</p>}
          {gmailActionError && <p className="text-xs text-danger mt-2">{gmailActionError}</p>}

          <div className="flex items-center gap-3 mt-3 pt-3 border-t border-border">
            {gmailStatus.needsReview > 0 && (
              <Button size="sm" onClick={() => navigate('/app/settings/gmail/review')}>
                Review {gmailStatus.needsReview}
              </Button>
            )}
            <Button variant="secondary" size="sm" loading={gmailDisconnecting} onClick={handleGmailDisconnect}>
              Disconnect
            </Button>
          </div>
        </div>
      )}
    </FinoraCard>
  );
}

function GmailUpgradePrompt() {
  const navigate = useNavigate();
  return (
    <div className="flex items-center justify-between gap-3">
      <div>
        <p className="text-sm text-ink font-medium">Gmail</p>
        <p className="text-2xs text-muted mt-0.5">
          Automatically detect receipts from your inbox -- a Premium feature.
        </p>
      </div>
      <Button size="sm" className="flex-shrink-0" onClick={() => navigate('/app/billing')}>
        <Crown size={12} /> Upgrade
      </Button>
    </div>
  );
}

function GmailSkeletonFields() {
  return (
    <div className="flex items-center justify-between gap-3">
      <div className="space-y-1.5 flex-1">
        <Skeleton.Text width="w-16" />
        <Skeleton.Text width="w-64" className="h-2.5" />
      </div>
      <Skeleton.Block className="h-7 w-28 flex-shrink-0" />
    </div>
  );
}
