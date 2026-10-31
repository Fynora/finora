import { describe, expect, it } from 'vitest';
import type { ActivationMarkerWindow, ActivationReleaseMarker, ActivationWeekRow, FunnelStep } from '../api/endpoints';
import { addDays, comparison, defaultMarker, markersIn } from './activationMarkers';

function marker(id: number, date: string, comparable = true): ActivationReleaseMarker {
  const w = comparable ? window(0, [0, 0, 0, 0, 0, 0]) : null;
  return { id, date, label: `M${id}`, before: w, after: w };
}

function window(cameBack: number, reached: number[]): ActivationMarkerWindow {
  const [s, o, c, f, r, g] = reached;
  return {
    from: '2026-10-01', to: '2026-10-10', signups: s, cameBackLaterDay: cameBack,
    reached: { SIGNED_UP: s, OPENED_IMPORT: o, CHOSE_FILE: c, FILE_READ: f, REVIEW_OPENED: r, GOT_DATA_IN: g },
  };
}

const week = (weekStart: string) => ({ kind: 'week' as const, row: { weekStart, counts: {} as Record<FunnelStep, number> } as ActivationWeekRow });

describe('addDays', () => {
  it('adds days across month and year ends', () => {
    expect(addDays('2026-10-26', 6)).toBe('2026-11-01');
    expect(addDays('2026-12-28', 6)).toBe('2027-01-03');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
  });
});

describe('markersIn', () => {
  const markers = [marker(1, '2026-10-19'), marker(2, '2026-10-25'), marker(3, '2026-10-26'), marker(4, '2026-11-09')];

  it('takes the markers from Monday to Sunday of a week, edges included', () => {
    expect(markersIn(week('2026-10-19'), markers).map((m) => m.id)).toEqual([1, 2]);
    expect(markersIn(week('2026-10-26'), markers).map((m) => m.id)).toEqual([3]);
    expect(markersIn(week('2026-11-02'), markers)).toEqual([]);
  });

  it('takes every marker inside a folded run of quiet weeks', () => {
    const quiet = { kind: 'quiet' as const, from: '2026-10-26', to: '2026-11-09', weeks: 3 };
    expect(markersIn(quiet, markers).map((m) => m.id)).toEqual([3, 4]);
  });
});

describe('defaultMarker', () => {
  it('is the latest marker whose day has come, none when there is none', () => {
    expect(defaultMarker([marker(1, '2026-10-01'), marker(2, '2026-10-20'), marker(3, '2026-12-01', false)])?.id).toBe(2);
    expect(defaultMarker([marker(3, '2026-12-01', false)])).toBeUndefined();
    expect(defaultMarker([])).toBeUndefined();
  });
});

describe('comparison', () => {
  it('shows signups, each step as a share of the window, came back, and changes in points', () => {
    const rows = comparison(window(1, [10, 8, 6, 4, 3, 2]), window(2, [8, 8, 6, 5, 4, 4]));

    expect(rows.map((r) => [r.label, r.before, r.after, r.change])).toEqual([
      ['Signups', '10', '8', '−2'],
      ['Opened import', '8 (80%)', '8 (100%)', '+20 pts'],
      ['Chose a file', '6 (60%)', '6 (75%)', '+15 pts'],
      ['File read', '4 (40%)', '5 (63%)', '+23 pts'],
      ['Opened the review', '3 (30%)', '4 (50%)', '+20 pts'],
      ['Activation (got data in)', '2 (20%)', '4 (50%)', '+30 pts'],
      ['Came back another day', '1', '2', '+1'],
    ]);
  });

  it('shows a dash for a rate when a window has no signups, and 0 for no change', () => {
    const rows = comparison(window(0, [0, 0, 0, 0, 0, 0]), window(0, [3, 1, 0, 0, 0, 0]));

    expect(rows[0].change).toBe('+3');
    expect(rows[1]).toMatchObject({ before: '0 (—)', after: '1 (33%)', change: '—' });
    expect(rows[rows.length - 1].change).toBe('0');
  });
});
