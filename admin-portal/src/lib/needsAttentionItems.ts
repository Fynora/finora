import type { LucideIcon } from 'lucide-react';
import { AlarmClock, AlertTriangle, Clock, Copy, Lock, ShieldAlert, Tag } from 'lucide-react';
import type { NeedsAttentionDto, OverdueHolds } from '../types';

export interface NeedsAttentionItem {
  count: number;
  icon: LucideIcon;
  label: string;
  to: string | null;
  linkLabel: string | null;
}

/**
 * Every field on NeedsAttentionDto turned into a display row, non-zero fields only -- shared by
 * Dashboard.tsx's own NeedsAttentionSection and NotificationBell.tsx (dashboard redesign PR3) so
 * the two surfaces can't silently drift on what "needs attention" means or how many rows are
 * showing. See the backend record's own doc comment for what each field represents.
 */
export function needsAttentionItems(data: NeedsAttentionDto, now: number = Date.now()): NeedsAttentionItem[] {
  const trustOverdue = data.trustHoldsOverdue ?? NONE_OVERDUE;
  const importOverdue = data.importHoldsOverdue ?? NONE_OVERDUE;
  return [
    // Holds past the 48-hour promise lead everything (Gate 1 spec §4): the user was given a time
    // and it has passed. Each kind links to the queue it sits in, and says how late the oldest is.
    {
      count: trustOverdue.count,
      icon: AlarmClock,
      label: overdueLabel(trustOverdue, 'held statement', now),
      to: '/held-statements',
      linkLabel: 'Open Held Statements',
    },
    {
      count: importOverdue.count,
      icon: AlarmClock,
      label: overdueLabel(importOverdue, 'held import', now),
      to: '/held-imports',
      linkLabel: 'Open Held Imports',
    },
    // The two holds come first: each is a user waiting on a person, after being told they will
    // hear back within 48 hours. In October 2026 holds sat 3-6 days because nothing here pointed
    // at them and the per-hold alert email was missed. Same icons as the Sidebar's links to the
    // two queues, so the row and the place it leads read as one thing.
    {
      count: data.statementsHeldForTrustReview,
      icon: ShieldAlert,
      label: data.statementsHeldForTrustReview === 1
        ? 'statement is waiting for trust review'
        : 'statements are waiting for trust review',
      to: '/held-statements',
      linkLabel: 'Open Held Statements',
    },
    {
      count: data.importsHeldForReview,
      icon: Clock,
      label: data.importsHeldForReview === 1
        ? 'import is held for review'
        : 'imports are held for review',
      to: '/held-imports',
      linkLabel: 'Open Held Imports',
    },
    {
      count: data.importsWithSkippedRowsToday,
      icon: AlertTriangle,
      label: 'imports had skipped rows today',
      to: '/diagnostics',
      linkLabel: 'View in Diagnostics',
    },
    {
      count: data.lockedAccounts,
      icon: Lock,
      label: 'accounts are currently locked out',
      to: '/users',
      linkLabel: 'Go to Users',
    },
    {
      count: data.transactionsNeedingCategoryReview,
      icon: Tag,
      label: 'transactions still need category review',
      to: null,
      linkLabel: null,
    },
    {
      count: data.transactionsFlaggedAsDuplicates,
      icon: Copy,
      label: 'transactions are flagged as potential duplicates',
      to: null,
      linkLabel: null,
    },
  ].filter((item) => item.count > 0);
}

const NONE_OVERDUE: OverdueHolds = { count: 0, oldestHeldSince: null };

/** "held statements are past the 48-hour promise — oldest waiting 3 days"; the age is left off if
 *  the oldest time is missing or unreadable rather than shown wrong. */
function overdueLabel(overdue: OverdueHolds, noun: string, now: number): string {
  const base = overdue.count === 1
    ? `${noun} is past the 48-hour promise`
    : `${noun}s are past the 48-hour promise`;
  const since = overdue.oldestHeldSince ? Date.parse(overdue.oldestHeldSince) : NaN;
  if (Number.isNaN(since) || since > now) return base;
  const hours = Math.floor((now - since) / 3_600_000);
  const days = Math.floor(hours / 24);
  const age = days >= 1 ? `${days} day${days === 1 ? '' : 's'}` : `${hours} hour${hours === 1 ? '' : 's'}`;
  return `${base} — oldest waiting ${age}`;
}
