import type { ActivationReached, ActivationReachedRow, ActivationWeekRow, FunnelStep, ReachedStep } from '../api/endpoints';

/** The main path, in order (ReachedStep.java). */
export const REACHED_LABELS: Record<ReachedStep, string> = {
  SIGNED_UP: 'Signed up',
  OPENED_IMPORT: 'Opened import',
  CHOSE_FILE: 'Chose a file',
  FILE_READ: 'File read',
  REVIEW_OPENED: 'Opened the review',
  GOT_DATA_IN: 'Got data in',
};
export const REACHED_ORDER = Object.keys(REACHED_LABELS) as ReachedStep[];

/** What the people in a step's drop did not do, after "X of N". */
const DROP_PHRASES: Record<ReachedStep, string> = {
  SIGNED_UP: '',
  OPENED_IMPORT: 'never opened import',
  CHOSE_FILE: 'opened import but never chose a file',
  FILE_READ: 'chose a file but never had one read',
  REVIEW_OPENED: 'had a file read but never opened the review',
  GOT_DATA_IN: 'opened the review but never confirmed',
};

/** The furthest steps whose people make up the drop into FILE_READ, as the stopped-at table names them. */
export const FILE_READ_DROP_STEPS: FunnelStep[] = ['CHOSE_FILE', 'REFUSED', 'READ_FAILED', 'HELD'];

/** A whole percent, or a dash when there is nothing to divide by. */
export function percent(part: number, whole: number): string {
  return whole === 0 ? '—' : `${Math.round((part / whole) * 100)}%`;
}

/** The step with the largest drop from the one before; the earlier step on a tie; null when no step loses anyone. */
export function biggestDrop(reached: ActivationReached): { row: ActivationReachedRow; from: number } | null {
  let best: { row: ActivationReachedRow; from: number } | null = null;
  for (let i = 1; i < reached.steps.length; i++) {
    const row = reached.steps[i];
    if (row.lost > 0 && (!best || row.lost > best.row.lost)) best = { row, from: reached.steps[i - 1].reached };
  }
  return best;
}

/**
 * One line that says where signups get stuck: the biggest drop with its counts, the activation rate
 * (got data in, of every counted signup) and how many came back another day.
 */
export function verdict(reached: ActivationReached, cameBackLaterDay: number): string {
  if (reached.signups === 0) return 'No signups counted yet.';
  const gotDataIn = reached.steps.find((s) => s.step === 'GOT_DATA_IN')?.reached ?? 0;
  const drop = biggestDrop(reached);
  let first: string;
  if (!drop) {
    first = `No drop-off: all ${reached.signups} got data in.`;
  } else {
    const before = drop.row.lostBeforeFunnelRecord;
    const caveat = before > 0
      ? ` (${before} of them signed up before import-screen visits were recorded)`
      : '';
    first = `Biggest drop: ${drop.row.lost} of ${drop.from} ${DROP_PHRASES[drop.row.step]}${caveat}.`;
  }
  return `${first} Activation ${percent(gotDataIn, reached.signups)} (${gotDataIn} of ${reached.signups} got data in). `
    + `${cameBackLaterDay} came back another day.`;
}

export type WeekLine =
  | { kind: 'week'; row: ActivationWeekRow }
  /** One or more weeks in a row with no signups, shown as one line. */
  | { kind: 'quiet'; from: string; to: string; weeks: number };

export const DEFAULT_WEEKS = 8;

function signups(row: ActivationWeekRow): number {
  return Object.values(row.counts).reduce((sum, n) => sum + (n ?? 0), 0);
}

/**
 * The weekly rows to show: the last {@link DEFAULT_WEEKS} unless all are asked for, with each run of
 * weeks that had no signups folded into one line. {@code shown} is the rows the lines cover, for the
 * total row; {@code hidden} is how many earlier weeks are left out.
 */
export function weekLines(rows: ActivationWeekRow[], showAll: boolean): {
  lines: WeekLine[]; shown: ActivationWeekRow[]; hidden: number;
} {
  const shown = showAll ? rows : rows.slice(-DEFAULT_WEEKS);
  const lines: WeekLine[] = [];
  for (const row of shown) {
    const last = lines[lines.length - 1];
    if (signups(row) > 0) {
      lines.push({ kind: 'week', row });
    } else if (last?.kind === 'quiet') {
      lines[lines.length - 1] = { ...last, to: row.weekStart, weeks: last.weeks + 1 };
    } else {
      lines.push({ kind: 'quiet', from: row.weekStart, to: row.weekStart, weeks: 1 });
    }
  }
  return { lines, shown, hidden: rows.length - shown.length };
}
