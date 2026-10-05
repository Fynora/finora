import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft } from 'lucide-react';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { DataTable, type DataTableColumn } from '../../components/DataTable';
import { adminPushCampaignApi } from '../../api/endpoints';
import type { PushCampaign, PushCampaignRun } from '../../types';
import { estimateMinutes, formatIst, formatIstDate, PEOPLE_PER_MINUTE } from '../../lib/istTime';
import {
  apiMessage, AUDIENCE_LABELS, CAMPAIGN_STATUS_LABELS, campaignStatusTone, RUN_STATUS_LABELS,
  runStatusTone, scheduleSummary,
} from './labels';

/** Above this many recipients, sending now needs the word SEND typed, not just a click. */
export const TYPED_CONFIRM_ABOVE = 1000;

/** The API returns at most this many runs per campaign (newest first). */
export const RUN_HISTORY_LIMIT = 50;

type Action = 'send-now' | 'start' | 'pause' | 'resume' | 'stop' | 'cancel-sending';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const BTN = 'text-sm font-medium rounded-lg px-3.5 py-2 border border-border text-ink hover:bg-bg disabled:opacity-40';
const BTN_PRIMARY = 'text-sm font-semibold rounded-lg px-4 py-2 bg-primary hover:bg-primary-dark text-on-primary disabled:opacity-40';
const BTN_DANGER = 'text-sm font-medium rounded-lg px-3.5 py-2 border border-danger text-danger hover:bg-danger-bg disabled:opacity-40';

/** True while anything is still being queued or delivered, so the page keeps refreshing itself. */
function isActive(runs: PushCampaignRun[]): boolean {
  return runs.some((r) => r.status === 'RUNNING' || (r.pending ?? 0) > 0);
}

function DeliveryCell({ run }: { run: PushCampaignRun }) {
  if (run.sent === null) return <span className="text-muted">—</span>;
  const parts: [string, number | null, string?][] = [
    ['delivered', run.sent, 'text-success'],
    ['waiting', run.pending],
    ['failed', run.failed, (run.failed ?? 0) > 0 ? 'text-danger' : undefined],
    ['no working device', run.skipped],
    ['cancelled', run.cancelled],
  ];
  return (
    <div className="text-xs space-y-0.5">
      {parts
        .filter(([label, n]) => label === 'delivered' || (n ?? 0) > 0)
        .map(([label, n, tone]) => (
          <p key={label} className={tone ?? 'text-muted'}>{(n ?? 0).toLocaleString('en-IN')} {label}</p>
        ))}
    </div>
  );
}

export function CampaignDetail({
  id, onBack, onEdit, onCloned,
}: {
  id: string;
  onBack: () => void;
  onEdit: (campaign: PushCampaign) => void;
  onCloned: (campaign: PushCampaign) => void;
}) {
  const queryClient = useQueryClient();
  const [confirm, setConfirm] = useState<Action | null>(null);
  const [typed, setTyped] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [testTarget, setTestTarget] = useState('');
  const [testResult, setTestResult] = useState<{ queued: boolean; detail: string } | null>(null);

  const detail = useQuery({
    queryKey: ['push-campaign', id],
    queryFn: () => adminPushCampaignApi.get(id),
    // Counts move while the dispatcher works; stop polling once nothing is queued or sending.
    refetchInterval: (query) => (query.state.data && isActive(query.state.data.runs) ? 5000 : false),
  });

  const campaign = detail.data?.campaign;
  const runs = detail.data?.runs ?? [];

  const audience = useQuery({
    queryKey: ['push-campaign-audience-count', campaign?.audienceType],
    queryFn: () => adminPushCampaignApi.audienceCount(campaign!.audienceType),
    enabled: confirm === 'send-now' && campaign !== undefined,
    // The number in a send confirmation must be today's, not whatever was cached half a minute ago.
    staleTime: 0,
    gcTime: 0,
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['push-campaign', id] });
    void queryClient.invalidateQueries({ queryKey: ['push-campaigns'] });
  }

  function closeConfirm() {
    setConfirm(null);
    setTyped('');
  }

  const act = useMutation({
    mutationFn: async (action: Action): Promise<string> => {
      switch (action) {
        case 'send-now': {
          const run = await adminPushCampaignApi.sendNow(id);
          return `Sending started (${run.audienceSize.toLocaleString('en-IN')} people in the audience). Delivery counts below update on their own.`;
        }
        case 'start':
          await adminPushCampaignApi.start(id);
          return 'Scheduled. It will go out at the next slot.';
        case 'pause':
          await adminPushCampaignApi.pause(id);
          return 'Paused. Nothing more will be sent until you resume it.';
        case 'resume':
          await adminPushCampaignApi.resume(id);
          return 'Resumed.';
        case 'stop':
          await adminPushCampaignApi.stop(id);
          return 'Campaign ended. Anything queued but not yet sent was withdrawn.';
        case 'cancel-sending': {
          const r = await adminPushCampaignApi.cancelSending(id);
          return `Withdrew ${r.cancelledPushes.toLocaleString('en-IN')} queued push(es) and gave ${r.releasedSlots.toLocaleString('en-IN')} people their daily slot back. Pushes already handed to the phone service (at most one batch) still go out.`;
        }
      }
    },
    onSuccess: (message) => {
      setNotice(message);
      setError(null);
      closeConfirm();
      refresh();
    },
    onError: (err) => {
      setNotice(null);
      setError(apiMessage(err, 'That did not work.'));
      closeConfirm();
      refresh();
    },
  });

  const clone = useMutation({
    mutationFn: () => adminPushCampaignApi.clone(id),
    onSuccess: (copy) => {
      void queryClient.invalidateQueries({ queryKey: ['push-campaigns'] });
      onCloned(copy);
    },
    onError: (err) => setError(apiMessage(err, 'Could not clone the campaign.')),
  });

  const test = useMutation({
    mutationFn: () => {
      const target = testTarget.trim();
      return adminPushCampaignApi.sendTest(id, UUID.test(target) ? { userId: target } : { email: target });
    },
    onSuccess: (result) => {
      setTestResult(result);
      refresh();
    },
    onError: (err) => setTestResult({ queued: false, detail: apiMessage(err, 'The test could not be sent.') }),
  });

  if (detail.isLoading) return <p className="text-muted text-sm" role="status">Loading…</p>;
  if (detail.isError || !campaign) {
    return (
      <div className="space-y-3">
        <button type="button" onClick={onBack} className="text-sm text-accent hover:underline">Back to campaigns</button>
        <p className="text-sm text-danger">{apiMessage(detail.error, 'Could not load this campaign.')}</p>
      </div>
    );
  }

  const status = campaign.status;
  const scheduled = campaign.scheduleKind !== 'NOW_ONLY';
  const canEdit = status === 'DRAFT' || status === 'PAUSED';
  const canSendNow = status === 'DRAFT' || status === 'ACTIVE' || status === 'PAUSED';
  const finished = status === 'STOPPED' || status === 'COMPLETED';

  const count = audience.data?.count;
  const limit = audience.data?.rolloutLimit;
  const overLimit = count !== undefined && limit !== undefined && count > limit;
  // If the count could not be fetched the size is unknown, so treat it as large: the typed word is
  // the guard that does not depend on a number the screen failed to get.
  const needsTyped = count === undefined ? audience.isError : count > TYPED_CONFIRM_ABOVE;

  const sendNowMessage = (() => {
    if (audience.isLoading) return 'Counting who this would reach…';
    if (audience.isError || count === undefined) return 'Could not count the audience, so the server will check it when you confirm.';
    const base = `This sends to about ${count.toLocaleString('en-IN')} people right now, at any hour.`;
    const when = `At the current delivery pace (about ${PEOPLE_PER_MINUTE} a minute, from the code, not measured on real phones) that is roughly ${estimateMinutes(count)} minute(s).`;
    const effect = campaign.scheduleKind === 'DAILY_AT'
      ? ' If today\'s slot has not gone out yet, this counts as today\'s send.'
      : ' This is the campaign\'s only send; it finishes afterwards.';
    return `${base} ${when}${effect}${overLimit ? ` That is over the rollout limit of ${limit!.toLocaleString('en-IN')}, so the server will refuse it.` : ''}`;
  })();

  const confirmCopy: Record<Action, { title: string; message: string; label: string; danger?: boolean }> = {
    'send-now': { title: 'Send this now?', message: sendNowMessage, label: 'Send now' },
    start: {
      title: 'Start this campaign?',
      message: `Schedule: ${scheduleSummary(campaign)}. You can pause or end it any time.`,
      label: 'Start',
    },
    pause: { title: 'Pause this campaign?', message: 'Nothing more will be sent until you resume it. Pushes already queued still go.', label: 'Pause' },
    resume: { title: 'Resume this campaign?', message: 'It picks up at its next slot.', label: 'Resume' },
    stop: {
      title: 'End this campaign for good?',
      message: 'No further sends, ever, and it cannot be resumed. Anything queued but not yet delivered is withdrawn. To send the same words again you would clone it. To only hold it for now, pause it instead.',
      label: 'End campaign', danger: true,
    },
    'cancel-sending': {
      title: 'Stop sending right now?',
      message: 'Withdraws every push of this campaign that is queued but not yet delivered, and gives those people their daily slot back. It does not end a daily campaign (use Stop for that). Pushes already handed to the phone service, at most one batch, still go out.',
      label: 'Stop sending', danger: true,
    },
  };

  const runColumns: DataTableColumn<PushCampaignRun>[] = [
    {
      header: 'Day (IST)',
      render: (r) => (
        <div>
          <p className="text-ink">{formatIstDate(r.runDateIst)}</p>
          <p className="text-muted text-xs">{formatIst(r.startedAt)}</p>
        </div>
      ),
    },
    { header: 'Started by', render: (r) => (r.triggeredBy === 'ADMIN_NOW' ? 'An admin (Send now)' : 'The schedule'), cellClassName: 'text-muted' },
    {
      header: 'Status',
      render: (r) => (
        <div>
          <span className={`inline-block rounded-full border px-2 py-0.5 text-xs ${runStatusTone(r.status)}`}>
            {RUN_STATUS_LABELS[r.status]}
          </span>
          {r.note && <p className="text-muted text-xs mt-1 max-w-xs">{r.note}</p>}
        </div>
      ),
    },
    {
      header: 'People',
      render: (r) => (
        <div className="text-xs space-y-0.5">
          <p className="text-ink">{r.audienceSize.toLocaleString('en-IN')} in audience</p>
          <p className="text-muted">{r.queuedCount.toLocaleString('en-IN')} queued</p>
          {r.skippedCapCount > 0 && <p className="text-muted">{r.skippedCapCount.toLocaleString('en-IN')} already had a push today</p>}
          {r.skippedAlreadyQueuedCount > 0 && <p className="text-muted">{r.skippedAlreadyQueuedCount.toLocaleString('en-IN')} already queued</p>}
        </div>
      ),
    },
    { header: 'Delivery', render: (r) => <DeliveryCell run={r} /> },
  ];

  return (
    <div className="space-y-6">
      <button type="button" onClick={onBack} className="inline-flex items-center gap-1.5 text-sm text-accent hover:underline">
        <ArrowLeft size={14} /> All campaigns
      </button>

      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-semibold text-ink break-words">{campaign.name}</h2>
            <span className={`inline-block rounded-full border px-2 py-0.5 text-xs ${campaignStatusTone(status)}`}>
              {CAMPAIGN_STATUS_LABELS[status]}
            </span>
          </div>
          <p className="text-sm text-muted mt-1">{scheduleSummary(campaign)}</p>
          {status === 'ACTIVE' && campaign.nextRunAt && (
            <p className="text-sm text-muted">Next send: {formatIst(campaign.nextRunAt)}</p>
          )}
          <p className="text-sm text-muted">Audience: {AUDIENCE_LABELS[campaign.audienceType]}</p>
        </div>

        <div className="flex flex-wrap gap-2">
          {canEdit && <button type="button" className={BTN} onClick={() => onEdit(campaign)}>Edit</button>}
          {status === 'DRAFT' && scheduled && (
            <button type="button" className={BTN_PRIMARY} disabled={act.isPending} onClick={() => setConfirm('start')}>Start</button>
          )}
          {status === 'PAUSED' && scheduled && (
            <button type="button" className={BTN_PRIMARY} disabled={act.isPending} onClick={() => setConfirm('resume')}>Resume</button>
          )}
          {status === 'ACTIVE' && (
            <button type="button" className={BTN} disabled={act.isPending} onClick={() => setConfirm('pause')}>Pause</button>
          )}
          {canSendNow && (
            <button type="button" className={BTN} disabled={act.isPending} onClick={() => setConfirm('send-now')}>Send now</button>
          )}
          <button type="button" className={BTN} disabled={clone.isPending} onClick={() => clone.mutate()}>Clone</button>
          {!finished && (
            <button type="button" className={BTN_DANGER} disabled={act.isPending} onClick={() => setConfirm('stop')}>End campaign</button>
          )}
        </div>
      </div>

      {notice && <p className="text-sm text-success bg-success-bg rounded-lg px-3 py-2" role="status">{notice}</p>}
      {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3 py-2" role="alert">{error}</p>}

      <div className="grid gap-5 lg:grid-cols-2">
        <div className="bg-card border border-border rounded-xl2 p-5 space-y-3">
          <h3 className="font-semibold text-ink text-sm">What people see</h3>
          <div className="rounded-2xl border border-border bg-bg p-3">
            <p className="text-[11px] text-muted mb-1">Fynora · now</p>
            <p className="text-sm font-semibold text-ink break-words">{campaign.title}</p>
            <p className="text-sm text-muted whitespace-pre-line break-words">{campaign.message}</p>
          </div>
        </div>

        <div className="bg-card border border-border rounded-xl2 p-5 space-y-3">
          <h3 className="font-semibold text-ink text-sm">Send a test first</h3>
          <p className="text-xs text-muted">
            Goes to one person's phone only. Use an email or a user id; an email always means the end-user
            account, never an admin account. A test never counts toward anyone's daily limit.
          </p>
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              setTestResult(null);
              if (testTarget.trim()) test.mutate();
            }}
          >
            <input
              aria-label="Test recipient email or user id"
              className="flex-1 bg-bg border border-border rounded-lg px-3 py-2 text-sm"
              placeholder="email or user id"
              value={testTarget}
              onChange={(e) => setTestTarget(e.target.value)}
            />
            <button type="submit" className={BTN} disabled={test.isPending || !testTarget.trim()}>
              {test.isPending ? 'Sending…' : 'Send test'}
            </button>
          </form>
          {testResult && (
            <p className={`text-sm ${testResult.queued ? 'text-success' : 'text-danger'}`} role="status">{testResult.detail}</p>
          )}
          <p className="text-xs text-muted">
            {campaign.lastTestedAt ? `Last tested ${formatIst(campaign.lastTestedAt)}.` : 'Not tested yet.'}
          </p>
        </div>
      </div>

      <div className="bg-card border border-border rounded-xl2 p-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="font-semibold text-ink text-sm">Emergency brake</h3>
          <p className="text-xs text-muted max-w-xl">
            Sent the wrong thing? Withdraw everything queued but not yet delivered. Works in any status,
            including a one-off that has already launched.
          </p>
        </div>
        <button type="button" className={BTN_DANGER} disabled={act.isPending} onClick={() => setConfirm('cancel-sending')}>
          Stop sending now
        </button>
      </div>

      <div className="space-y-2">
        <h3 className="font-semibold text-ink text-sm">Sends so far</h3>
        <DataTable
          columns={runColumns}
          rows={runs}
          keyFor={(r) => r.id}
          loading={false}
          emptyMessage="Nothing has been sent yet."
        />
        {runs.length >= RUN_HISTORY_LIMIT && (
          <p className="text-xs text-muted">Showing the newest {RUN_HISTORY_LIMIT} sends.</p>
        )}
        {runs.some((r) => r.status !== 'MISSED') && (
          <p className="text-xs text-muted">
            Delivery counts are live. "No working device" means the app was uninstalled or signed out: routine, not a failure.
          </p>
        )}
      </div>

      {confirm && (
        <ConfirmDialog
          title={confirmCopy[confirm].title}
          message={confirmCopy[confirm].message}
          confirmLabel={confirmCopy[confirm].label}
          danger={confirmCopy[confirm].danger}
          busy={act.isPending}
          confirmDisabled={confirm === 'send-now' && (audience.isLoading || overLimit || (needsTyped && typed.trim() !== 'SEND'))}
          onConfirm={() => act.mutate(confirm)}
          onCancel={closeConfirm}
        >
          {confirm === 'send-now' && needsTyped && (
            <div>
              <label htmlFor="push-send-confirm" className="text-xs font-medium text-muted mb-1 block">
                This is a large send. Type SEND to confirm.
              </label>
              <input
                id="push-send-confirm"
                autoComplete="off"
                className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
              />
            </div>
          )}
        </ConfirmDialog>
      )}
    </div>
  );
}
