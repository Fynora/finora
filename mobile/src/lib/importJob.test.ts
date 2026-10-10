import { HELD_DETAIL, HELD_LABEL, HELD_OVERDUE_DETAIL, detail, heldDetail,isCancellable, isHeld, isReviewable, isSettled, label, percent } from './importJob';
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
    expect(label(trustHeld())).toBe(HELD_LABEL);
  });

  it('reads exactly like the other hold, because the user is in the same situation', () => {
    const otherHold = job({ status: 'HELD_FOR_REVIEW', userStatus: 'HELD_FOR_REVIEW' });
    expect(label(trustHeld())).toBe(label(otherHold));
    expect(detail(trustHeld())).toBe(detail(otherHold));
  });

  it('says a person is checking it and gives the 48-hour limit, never questioning the statement', () => {
    // A time, because testers read an open-ended wait as the app having done nothing (see
    // HELD_DETAIL). "By hand", because it is true and it is why the wait exists.
    const text = detail(trustHeld()) ?? '';
    expect(text).toContain('by hand');
    expect(text).toContain('48 hours');
    for (const forbidden of ['genuine', 'authentic', 'verify', 'legitimate', 'fraud', 'suspicious']) {
      expect(text.toLowerCase()).not.toContain(forbidden);
    }
  });

  it('promises a notification and tells the user they can carry on meanwhile', () => {
    const text = detail(trustHeld()) ?? '';
    expect(text).toContain("we'll notify you");
    expect(text).toContain('keep using Fynora');
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
    expect(label(held())).toBe(HELD_LABEL);
  });

  it('explains itself even though no rows were ever counted', () => {
    expect(held().rowsTotal).toBeNull();
    expect(detail(held())).toBe(HELD_DETAIL);
  });

  it('says a person is checking it and gives the 48-hour limit, never questioning the statement', () => {
    // A time, because testers read an open-ended wait as the app having done nothing (see
    // HELD_DETAIL). "By hand", because it is true and it is why the wait exists.
    const text = detail(held()) ?? '';
    expect(text).toContain('by hand');
    expect(text).toContain('48 hours');
    for (const forbidden of ['genuine', 'authentic', 'verify', 'legitimate', 'fraud', 'suspicious']) {
      expect(text.toLowerCase()).not.toContain(forbidden);
    }
  });

  it('promises a notification and tells the user they can carry on meanwhile', () => {
    const text = detail(held()) ?? '';
    expect(text).toContain("we'll notify you");
    expect(text).toContain('keep using Fynora');
  });

  it('shows no progress percentage — nothing is running', () => {
    expect(percent(held())).toBeNull();
  });
});

describe('importJob — a hold past its 48-hour promise', () => {
  // Gate 1 spec §4. The server decides (holdOverdue); the client only renders it.
  const held = (over: Partial<ImportJobProgress> = {}) =>
    job({ status: 'HELD_FOR_TRUST_REVIEW', userStatus: 'HELD_FOR_REVIEW', ...over });

  it('keeps the 48-hour copy while the hold is within its promise', () => {
    expect(detail(held({ holdOverdue: false }))).toBe(HELD_DETAIL);
    expect(heldDetail(held({ holdOverdue: false }))).toBe(HELD_DETAIL);
  });

  it('apologises instead once the hold is overdue, for both holds alike', () => {
    expect(detail(held({ holdOverdue: true }))).toBe(HELD_OVERDUE_DETAIL);
    expect(detail(held({ status: 'HELD_FOR_REVIEW', holdOverdue: true }))).toBe(HELD_OVERDUE_DETAIL);
    expect(HELD_OVERDUE_DETAIL).toBe(
      'This is taking longer than we promised — sorry. You can keep waiting, or upload a different '
      + "statement in the meantime. We'll notify you as soon as this one is done.",
    );
  });

  it('never repeats the broken 48-hour promise and never questions the statement', () => {
    expect(HELD_OVERDUE_DETAIL).not.toContain('48 hours');
    for (const forbidden of ['genuine', 'authentic', 'verify', 'legitimate', 'fraud', 'suspicious']) {
      expect(HELD_OVERDUE_DETAIL.toLowerCase()).not.toContain(forbidden);
    }
  });

  it('keeps the normal copy when a server that predates holdOverdue omits it', () => {
    expect(detail(held())).toBe(HELD_DETAIL);
  });

  it('ignores a stray holdOverdue on a job that is not held', () => {
    expect(detail(job({ status: 'COMPLETED', userStatus: 'COMPLETED', rowsTotal: 3, holdOverdue: true })))
      .toBe('3 transactions found');
    expect(detail(job({ status: 'FAILED', userStatus: 'FAILED', holdOverdue: true }))).toBeNull();
  });
});

describe('importJob — not held', () => {
  it('reports every in-flight and terminal-but-final status as not held', () => {
    for (const status of ['QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING', 'IMPORTING', 'LEARNING', 'COMPLETED', 'FAILED', 'CANCELLED'] as const) {
      expect(isHeld(job({ status }))).toBe(false);
    }
  });
});
