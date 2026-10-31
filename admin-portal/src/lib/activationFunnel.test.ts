import { describe, expect, it } from 'vitest';
import type { ActivationReached, ActivationWeekRow, FunnelStep, ReachedStep } from '../api/endpoints';
import { biggestDrop, percent, verdict, weekLines } from './activationFunnel';

const FUNNEL: FunnelStep[] = [
  'SIGNED_UP', 'OPENED_IMPORT', 'CHOSE_FILE', 'REFUSED', 'READ_FAILED', 'HELD',
  'READ_NOT_REVIEWED', 'REVIEWED_NOT_CONFIRMED', 'GOT_DATA_IN',
];
const PATH: ReachedStep[] = ['SIGNED_UP', 'OPENED_IMPORT', 'CHOSE_FILE', 'FILE_READ', 'REVIEW_OPENED', 'GOT_DATA_IN'];

/** Reached counts in path order; lost is worked out from them. */
function reached(counts: number[], lostBefore: Partial<Record<ReachedStep, number>> = {}): ActivationReached {
  return {
    signups: counts[0],
    steps: PATH.map((step, i) => ({
      step, reached: counts[i], lost: i === 0 ? 0 : counts[i - 1] - counts[i], lostBeforeFunnelRecord: lostBefore[step] ?? 0,
    })),
    stoppedAt: Object.fromEntries(FUNNEL.map((s) => [s, 0])) as Record<FunnelStep, number>,
  };
}

function week(weekStart: string, signedUp: number): ActivationWeekRow {
  const counts = Object.fromEntries(FUNNEL.map((s) => [s, 0])) as Record<FunnelStep, number>;
  counts.SIGNED_UP = signedUp;
  return { weekStart, counts };
}

describe('verdict', () => {
  it('names the biggest drop with its counts, the activation rate and who came back', () => {
    expect(verdict(reached([24, 15, 10, 8, 7, 6]), 1)).toBe(
      'Biggest drop: 9 of 24 never opened import. Activation 25% (6 of 24 got data in). 1 came back another day.',
    );
  });

  it('says how many of the drop signed up before import-screen visits were recorded', () => {
    expect(verdict(reached([24, 15, 10, 8, 7, 6], { OPENED_IMPORT: 5 }), 0)).toBe(
      'Biggest drop: 9 of 24 never opened import (5 of them signed up before import-screen visits were recorded). '
      + 'Activation 25% (6 of 24 got data in). 0 came back another day.',
    );
  });

  it('picks the earlier step when two drops are the same size', () => {
    const drop = biggestDrop(reached([10, 10, 7, 4, 4, 4]));
    expect(drop?.row.step).toBe('CHOSE_FILE');
    expect(drop?.from).toBe(10);
  });

  it('describes a drop later in the path in its own words', () => {
    expect(verdict(reached([10, 10, 10, 10, 9, 2]), 0)).toMatch(/^Biggest drop: 7 of 9 opened the review but never confirmed\./);
    expect(verdict(reached([10, 10, 10, 3, 3, 3]), 0)).toMatch(/^Biggest drop: 7 of 10 chose a file but never had one read\./);
  });

  it('says so when there are no signups', () => {
    expect(verdict(reached([0, 0, 0, 0, 0, 0]), 0)).toBe('No signups counted yet.');
  });

  it('says there is no drop when everyone got data in', () => {
    expect(verdict(reached([3, 3, 3, 3, 3, 3]), 2)).toBe(
      'No drop-off: all 3 got data in. Activation 100% (3 of 3 got data in). 2 came back another day.',
    );
  });

  it('handles everyone stopping at the first step', () => {
    expect(verdict(reached([4, 0, 0, 0, 0, 0]), 0)).toBe(
      'Biggest drop: 4 of 4 never opened import. Activation 0% (0 of 4 got data in). 0 came back another day.',
    );
  });
});

describe('percent', () => {
  it('rounds to a whole percent and shows a dash for nothing to divide by', () => {
    expect(percent(1, 3)).toBe('33%');
    expect(percent(2, 3)).toBe('67%');
    expect(percent(0, 0)).toBe('—');
  });
});

describe('weekLines', () => {
  it('shows the last 8 weeks by default and says how many are left out', () => {
    const rows = Array.from({ length: 10 }, (_, i) => week(`2026-08-${String(i + 1).padStart(2, '0')}`, 1));
    const { lines, shown, hidden } = weekLines(rows, false);
    expect(hidden).toBe(2);
    expect(shown).toHaveLength(8);
    expect(lines[0]).toEqual({ kind: 'week', row: rows[2] });

    const all = weekLines(rows, true);
    expect(all.hidden).toBe(0);
    expect(all.lines).toHaveLength(10);
  });

  it('folds each run of weeks with no signups into one line', () => {
    const rows = [
      week('2026-09-07', 2), week('2026-09-14', 0), week('2026-09-21', 0), week('2026-09-28', 1), week('2026-10-05', 0),
    ];
    expect(weekLines(rows, false).lines).toEqual([
      { kind: 'week', row: rows[0] },
      { kind: 'quiet', from: '2026-09-14', to: '2026-09-21', weeks: 2 },
      { kind: 'week', row: rows[3] },
      { kind: 'quiet', from: '2026-10-05', to: '2026-10-05', weeks: 1 },
    ]);
  });

  it('has no lines with no weeks', () => {
    expect(weekLines([], false)).toEqual({ lines: [], shown: [], hidden: 0 });
  });
});
