import { detail, isCancellable, isHeld, isReviewable, isSettled, label, percent } from './importJob';
import type { ImportJobProgress } from '../api/endpoints';

/**
 * Phase 4 (Medium-Tier Parity). Port of frontend/src/lib/importJob.test.ts, minus the
 * recentImportsRefetchIntervalMs describe blocks -- that function itself isn't ported (see
 * importJob.ts's own doc comment on why). Everything ported keeps the original's exact assertions.
 */

function job(over: Partial<ImportJobProgress> = {}): ImportJobProgress {
  return {
    jobId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    fileName: 'statement.csv',
    status: 'QUEUED',
    userStatus: 'PROCESSING',
    rowsTotal: null,
    rowsProcessed: 0,
    createdAt: '2026-08-08T09:00:00Z',
    startedAt: null,
    finishedAt: null,
    importSessionId: null,
    error: null,
    correlationId: null,
    ...over,
  };
}

describe('importJob — when to stop polling', () => {
  it('keeps polling through every stage a worker holds a job in', () => {
    for (const status of ['QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING', 'IMPORTING', 'LEARNING'] as const) {
      expect(isSettled(job({ status }))).toBe(false);
    }
  });

  it('stops on every terminal state, not just the happy one', () => {
    expect(isSettled(job({ status: 'COMPLETED' }))).toBe(true);
    expect(isSettled(job({ status: 'FAILED' }))).toBe(true);
    expect(isSettled(job({ status: 'CANCELLED' }))).toBe(true);
  });
});

describe('importJob — when there is something to review', () => {
  it('is ready only when the job both finished and left a session behind', () => {
    expect(isReviewable(job({ status: 'COMPLETED', importSessionId: 'session-1' }))).toBe(true);
  });

  it('is not ready on a completed job with no session', () => {
    expect(isReviewable(job({ status: 'COMPLETED', importSessionId: null }))).toBe(false);
  });

  it('is not ready on a cancelled job that happens to carry a session', () => {
    expect(isReviewable(job({ status: 'CANCELLED', importSessionId: 'session-1' }))).toBe(false);
  });
});

describe('importJob — when cancelling is still honest', () => {
  it('offers cancel up to the point of no return', () => {
    for (const status of ['QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING'] as const) {
      expect(isCancellable(job({ status }))).toBe(true);
    }
  });

  it('withdraws it once financial rows exist', () => {
    expect(isCancellable(job({ status: 'IMPORTING' }))).toBe(false);
    expect(isCancellable(job({ status: 'LEARNING' }))).toBe(false);
  });

  it('withdraws it from anything already finished', () => {
    expect(isCancellable(job({ status: 'COMPLETED' }))).toBe(false);
    expect(isCancellable(job({ status: 'FAILED' }))).toBe(false);
    expect(isCancellable(job({ status: 'CANCELLED' }))).toBe(false);
  });
});

describe('importJob — how far along', () => {
  it('declines to guess before the statement has been counted', () => {
    expect(percent(job({ status: 'PARSING', rowsTotal: null }))).toBeNull();
  });

  it('reports the fraction once there is a total', () => {
    expect(percent(job({ status: 'ANALYZING', rowsTotal: 200, rowsProcessed: 50 }))).toBe(25);
  });

  it('never exceeds 100, even if the counts disagree', () => {
    expect(percent(job({ status: 'ANALYZING', rowsTotal: 10, rowsProcessed: 99 }))).toBe(100);
  });

  it('is complete when the job is, whatever the row counts say', () => {
    expect(percent(job({ status: 'COMPLETED', rowsTotal: 200, rowsProcessed: 3 }))).toBe(100);
  });
});

describe('importJob — what the user is told', () => {
  it('describes the statement, not the queue', () => {
    expect(label(job({ status: 'ANALYZING' }))).toBe('Finding the transactions');
    expect(label(job({ status: 'QUEUED' }))).toBe('Waiting to start');
  });

  it('has nothing to add on a failure -- ImportProgressCard fetches the curated reason itself', () => {
    expect(detail(job({ status: 'FAILED', error: 'That PDF is password protected.' }))).toBeNull();
  });

  it('says nothing about counts it does not have', () => {
    expect(detail(job({ status: 'PARSING', rowsTotal: null }))).toBeNull();
  });

  it('counts transactions rather than rows once it is done', () => {
    expect(detail(job({ status: 'COMPLETED', rowsTotal: 1, rowsProcessed: 1 })))
      .toBe('1 transaction found');
    expect(detail(job({ status: 'COMPLETED', rowsTotal: 42, rowsProcessed: 42 })))
      .toBe('42 transactions found');
  });
});

describe('importJob — held for trust review', () => {
  const trustHeld = () => job({
    status: 'HELD_FOR_TRUST_REVIEW',
    userStatus: 'HELD_FOR_REVIEW',
    importSessionId: 'session-that-must-not-be-confirmable',
  });

  it('never offers the review step, even though a staged session exists', () => {
    expect(trustHeld().importSessionId).not.toBeNull();
    expect(isReviewable(trustHeld())).toBe(false);
  });

  it('is reported as held', () => {
    expect(isHeld(trustHeld())).toBe(true);
  });

  it('stops polling, because it waits on a person and not on the worker', () => {
    expect(isSettled(trustHeld())).toBe(true);
  });

  it('is not offered a Cancel button', () => {
    expect(isCancellable(trustHeld())).toBe(false);
  });

  it('reads as work in progress rather than as a failure', () => {
    expect(label(trustHeld())).toBe('Running additional checks');
  });

  it('reads exactly like the other hold, because the user is in the same situation', () => {
    const otherHold = job({ status: 'HELD_FOR_REVIEW', userStatus: 'HELD_FOR_REVIEW' });
    expect(label(trustHeld())).toBe(label(otherHold));
    expect(detail(trustHeld())).toBe(detail(otherHold));
  });

  it('promises no deadline and never questions the statement itself', () => {
    const text = detail(trustHeld()) ?? '';
    for (const forbidden of ['hour', 'minute', 'day', 'soon', 'shortly', 'within']) {
      expect(text.toLowerCase()).not.toContain(forbidden);
    }
    for (const forbidden of ['genuine', 'authentic', 'verify', 'legitimate', 'fraud', 'suspicious']) {
      expect(text.toLowerCase()).not.toContain(forbidden);
    }
  });

  it('tells the user there is nothing for them to do', () => {
    expect(detail(trustHeld())?.toLowerCase()).toContain('no action needed');
  });
});

describe('importJob — held for review', () => {
  const held = () => job({ status: 'HELD_FOR_REVIEW', userStatus: 'HELD_FOR_REVIEW' });

  it('is reported as held', () => {
    expect(isHeld(held())).toBe(true);
  });

  it('stops polling, because a held job waits on a person and not on the worker', () => {
    expect(isSettled(held())).toBe(true);
  });

  it('is not offered a Cancel button', () => {
    expect(isCancellable(held())).toBe(false);
  });

  it('is not reviewable — there is nothing staged to review', () => {
    expect(isReviewable(held())).toBe(false);
  });

  it('reads as work in progress rather than as a failure', () => {
    expect(label(held())).toBe('Running additional checks');
  });

  it('explains itself even though no rows were ever counted', () => {
    expect(held().rowsTotal).toBeNull();
    expect(detail(held())).toContain('additional checks');
  });

  it('promises no deadline and never questions the statement itself', () => {
    const text = detail(held()) ?? '';
    for (const forbidden of ['hour', 'minute', 'day', 'soon', 'shortly', 'within']) {
      expect(text.toLowerCase()).not.toContain(forbidden);
    }
    for (const forbidden of ['genuine', 'authentic', 'verify', 'legitimate', 'fraud', 'suspicious']) {
      expect(text.toLowerCase()).not.toContain(forbidden);
    }
  });

  it('tells the user there is nothing for them to do', () => {
    expect(detail(held())?.toLowerCase()).toContain('no action needed');
  });

  it('shows no progress percentage — nothing is running', () => {
    expect(percent(held())).toBeNull();
  });
});

describe('importJob — not held', () => {
  it('reports every in-flight and terminal-but-final status as not held', () => {
    for (const status of ['QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING', 'IMPORTING', 'LEARNING', 'COMPLETED', 'FAILED', 'CANCELLED'] as const) {
      expect(isHeld(job({ status }))).toBe(false);
    }
  });
});
