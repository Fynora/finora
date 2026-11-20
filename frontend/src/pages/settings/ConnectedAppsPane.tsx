import { useEffect, useRef, useState } from 'react';
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
import { trackNavigation } from '../../lib/trackNavigation';

type GmailNotice = { text: string; isError: boolean };

/** One-shot message for the `?gmail=` query param GoogleOAuthController's callback redirect
 *  lands with. Null for anything else (including no param at all).
 *
 *  There is no 'connected' here on purpose. The callback cannot know who is finishing the flow,
 *  so it never connects anything: a successful return arrives as `gmail=confirm` with a one-time
 *  ticket, and "Gmail connected." is shown only after the server has accepted that ticket from
 *  this signed-in account (see the effect below). A link carrying `gmail=connected` shows nothing. */
function gmailCallbackMessage(gmail: string | null): GmailNotice | null {
  switch (gmail) {
    case 'declined': return { text: 'Gmail connection was cancelled.', isError: false };
    case 'invalid':
    case 'failed':
      return { text: "Couldn't connect Gmail -- please try again.", isError: true };
    default: return null;
  }
}

// lastDiscoveryAt advances when discovery last completed cleanly ("we checked the mailbox");
// lastSyncedAt advances only when a run actually staged a transaction ("we synced something").
// The two can diverge -- a connection whose discovery keeps failing but whose extraction is
// still draining an existing backlog updates the second without the first -- so "Last synced"
// takes whichever is more recent rather than reading lastDiscoveryAt alone, which would say
// "never" next to transactions the user can already see in their review queue.
function mostRecentIso(a: string | null, b: string | null): string | null {
  if (!a) return b;
  if (!b) return a;
  const ta = new Date(a).getTime();
  const tb = new Date(b).getTime();
  // A malformed timestamp must never win by default: `NaN >= x` is always false in JS, so a
  // naive `ta >= tb ? a : b` would silently prefer a garbage value the moment either side
  // fails to parse, discarding a perfectly good date -- exactly the "shows never synced when
  // it isn't" failure this whole label exists to avoid.
  if (Number.isNaN(ta)) return b;
  if (Number.isNaN(tb)) return a;
  return ta >= tb ? a : b;
}

function gmailLastSyncedLabel(status: GmailConnectionStatus): string {
  const label = formatRelativeTime(mostRecentIso(status.lastDiscoveryAt, status.lastSyncedAt));
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
  const [gmailCallbackNotice, setGmailCallbackNotice] =
    useState(() => gmailCallbackMessage(searchParams.get('gmail')));
  // The ticket is single-use, so it must be posted exactly once. A ref, not state: React's
  // StrictMode runs a mount effect twice in development, and a second post would be refused and
  // overwrite the first one's success with an error.
  const gmailConfirmStarted = useRef(false);

  function loadGmailStatus() {
    setGmailLoading(true);
    setGmailError(false);
    gmailApi.status()
      .then(setGmailStatus)
      .catch(() => setGmailError(true))
      .finally(() => setGmailLoading(false));
  }

  useEffect(() => {
    // Back from Google with a ticket: the mailbox is not connected yet. Posting the ticket from
    // this signed-in session is what connects it, and the server refuses unless this account is
    // the one that started the flow. The status is loaded after the answer, either way.
    const ticket = searchParams.get('gmail') === 'confirm' ? searchParams.get('ticket') : null;
    if (ticket && !gmailConfirmStarted.current) {
      gmailConfirmStarted.current = true;
      gmailApi.confirmConnect(ticket)
        .then(() => setGmailCallbackNotice({ text: 'Gmail connected.', isError: false }))
        .catch((err) => {
          const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
          setGmailCallbackNotice({
            text: message || "Couldn't connect Gmail -- please try again.",
            isError: true,
          });
        })
        .finally(loadGmailStatus);
    } else if (!ticket) {
      if (searchParams.get('gmail') === 'confirm') {
        setGmailCallbackNotice({ text: "Couldn't connect Gmail -- please try again.", isError: true });
      }
      loadGmailStatus();
    }
    // Strip ?gmail=... and the ticket from the URL once read, so a page refresh doesn't replay a
    // stale message or post a spent ticket. Copies searchParams first and deletes only these
    // keys. Sets ?tab= to this pane: Settings opened it because of ?gmail= (the redirect carries
    // no ?tab=), and without this the page would fall back to General the moment ?gmail= is gone,
    // unmounting this pane before it can show the result.
    if (searchParams.has('gmail') || searchParams.has('ticket')) {
      const next = new URLSearchParams(searchParams);
      next.delete('gmail');
      next.delete('ticket');
      next.set('tab', 'connected-apps');
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
      <Button size="sm" className="flex-shrink-0" onClick={() => { trackNavigation('subscription', 'contextual'); void navigate('/app/billing'); }}>
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
