import type { ActivationMarkerWindow, ActivationReleaseMarker, ReachedStep } from '../api/endpoints';
import type { WeekLine } from './activationFunnel';
import { REACHED_LABELS, REACHED_ORDER } from './activationFunnel';

/** "2026-10-25" plus n days, as YYYY-MM-DD, worked on the date itself (no time zone involved). */
export function addDays(iso: string, n: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const t = new Date(Date.UTC(y, m - 1, d + n));
  return t.toISOString().slice(0, 10);
}

/** The markers dated inside a week line: one week (Monday to Sunday), or a folded run of quiet weeks. */
export function markersIn(line: WeekLine, markers: ActivationReleaseMarker[]): ActivationReleaseMarker[] {
  const from = line.kind === 'week' ? line.row.weekStart : line.from;
  const lastMonday = line.kind === 'week' ? line.row.weekStart : line.to;
  const to = addDays(lastMonday, 6);
  return markers.filter((m) => m.date >= from && m.date <= to);
}

/** The marker to compare by default: the latest whose day has come, else none. */
export function defaultMarker(markers: ActivationReleaseMarker[]): ActivationReleaseMarker | undefined {
  return [...markers].reverse().find((m) => m.before && m.after);
}

export interface ComparisonRow {
  key: string;
  label: string;
  before: string;
  after: string;
  /** After minus before: a count, or percentage points for a rate. */
  change: string;
}

function share(part: number, whole: number): number | null {
  return whole === 0 ? null : (part / whole) * 100;
}

function pct(value: number | null): string {
  return value === null ? '—' : `${Math.round(value)}%`;
}

function signed(n: number, unit = ''): string {
  if (n === 0) return `0${unit}`;
  return `${n > 0 ? '+' : '−'}${Math.abs(n)}${unit}`;
}

/**
 * The before/after table for one marker: signups, then each main-path step after the first as the
 * share of that window's signups who reached it, then who came back another day. A rate's change is
 * in percentage points, and a dash when either window has no signups.
 */
export function comparison(before: ActivationMarkerWindow, after: ActivationMarkerWindow): ComparisonRow[] {
  const rows: ComparisonRow[] = [{
    key: 'signups', label: 'Signups',
    before: String(before.signups), after: String(after.signups), change: signed(after.signups - before.signups),
  }];
  REACHED_ORDER.filter((s): s is ReachedStep => s !== 'SIGNED_UP').forEach((step) => {
    const b = share(before.reached[step] ?? 0, before.signups);
    const a = share(after.reached[step] ?? 0, after.signups);
    rows.push({
      key: step,
      label: step === 'GOT_DATA_IN' ? 'Activation (got data in)' : REACHED_LABELS[step],
      before: `${before.reached[step] ?? 0} (${pct(b)})`,
      after: `${after.reached[step] ?? 0} (${pct(a)})`,
      change: a === null || b === null ? '—' : signed(Math.round(a) - Math.round(b), ' pts'),
    });
  });
  rows.push({
    key: 'cameBack', label: 'Came back another day',
    before: String(before.cameBackLaterDay), after: String(after.cameBackLaterDay),
    change: signed(after.cameBackLaterDay - before.cameBackLaterDay),
  });
  return rows;
}
