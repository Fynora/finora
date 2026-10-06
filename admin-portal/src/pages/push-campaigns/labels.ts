import type { PushAudienceType, PushCampaign, PushCampaignStatus, PushRunStatus } from '../../types';
import { formatIst, formatIstDate, toTimeInput } from '../../lib/istTime';

export const AUDIENCE_LABELS: Record<PushAudienceType, string> = {
  ALL_WITH_DEVICE: 'Everyone with the app installed',
  NO_STATEMENT_UPLOADED: 'People who have not uploaded a statement yet',
};

export const AUDIENCE_HELP: Record<PushAudienceType, string> = {
  ALL_WITH_DEVICE:
    'Every active account that has the app on a phone and has not switched push notifications off.',
  NO_STATEMENT_UPLOADED:
    'The same people, but only those who have never uploaded a statement (no import in any state).',
};

export const CAMPAIGN_STATUS_LABELS: Record<PushCampaignStatus, string> = {
  DRAFT: 'Draft',
  ACTIVE: 'Scheduled',
  PAUSED: 'Paused',
  STOPPED: 'Stopped',
  COMPLETED: 'Finished',
};

export function campaignStatusTone(status: PushCampaignStatus): string {
  switch (status) {
    case 'ACTIVE':
      return 'bg-success-bg text-success border-success';
    case 'PAUSED':
      return 'bg-warning-bg text-warning border-warning';
    case 'STOPPED':
      return 'bg-danger-bg text-danger border-danger';
    case 'COMPLETED':
      return 'bg-info-bg text-info border-info';
    default:
      return 'bg-bg text-muted border-border';
  }
}

export const RUN_STATUS_LABELS: Record<PushRunStatus, string> = {
  RUNNING: 'Sending',
  DONE: 'Done',
  FAILED: 'Failed',
  MISSED: 'Missed',
  CANCELLED: 'Cancelled',
};

export function runStatusTone(status: PushRunStatus): string {
  switch (status) {
    case 'RUNNING':
      return 'bg-info-bg text-info border-info';
    case 'DONE':
      return 'bg-success-bg text-success border-success';
    case 'FAILED':
      return 'bg-danger-bg text-danger border-danger';
    case 'MISSED':
      return 'bg-warning-bg text-warning border-warning';
    default:
      return 'bg-bg text-muted border-border';
  }
}

/** One plain line for the list: when does this go out? */
export function scheduleSummary(c: Pick<PushCampaign, 'scheduleKind' | 'runAt' | 'sendTimeIst' | 'endsOn'>): string {
  switch (c.scheduleKind) {
    case 'NOW_ONLY':
      return 'Only when you press Send now';
    case 'ONCE_AT':
      return `Once, ${formatIst(c.runAt)}`;
    case 'DAILY_AT':
      return `Every day at ${toTimeInput(c.sendTimeIst)} IST${c.endsOn ? `, until ${formatIstDate(c.endsOn)}` : ', until stopped'}`;
  }
}

/** "1 campaign push a day" / "3 campaign pushes a day". */
export function pushesPerDay(n: number): string {
  return `${n} campaign ${n === 1 ? 'push' : 'pushes'} a day`;
}

/** The server's own message when it gave one, otherwise a generic fallback. */
export function apiMessage(err: unknown, fallback: string): string {
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message ?? fallback;
}
