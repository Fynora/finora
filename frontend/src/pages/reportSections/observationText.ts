import type { InsightsData } from '../../api/endpoints';

export interface Observation {
  key: string;
  text: string;
}

/** The server's own bar for a category move worth saying out loud (InsightsService
 *  MOVER_SIGNIFICANCE_THRESHOLD_PCT). Below it a move is ordinary month-to-month noise. */
export const SIGNIFICANT_MOVE_PCT = 15;

const MAX_MOVES = 2;

/**
 * What is worth noticing about the month, in plain sentences, from the structured insight fields:
 * the categories that moved most against the user's recent average, and one category that is new.
 * The server's own sentences are not reused: the first just restates the donut's total (with the
 * raw "2026-09" label), and a sentence cannot be filtered reliably by its text. The catch-all
 * "Uncategorized" bucket is left out, since a rise in it is not a spending behaviour.
 *
 * Empty when the insights are absent or there is nothing to say; the section is then not drawn at
 * all rather than filled.
 */
export function buildObservations(
  insights: InsightsData | null,
  monthName: string,
  fmt: (n: number) => string,
): Observation[] {
  if (!insights) return [];
  const movers = (insights.movers ?? []).filter((m) => m.category.trim().toLowerCase() !== 'uncategorized');
  const out: Observation[] = [];

  movers
    .filter((m) => m.pctChange != null && Number.isFinite(m.pctChange) && Math.abs(m.pctChange) >= SIGNIFICANT_MOVE_PCT)
    .sort((a, b) => Math.abs(b.pctChange!) - Math.abs(a.pctChange!))
    .slice(0, MAX_MOVES)
    .forEach((m) => {
      const pct = Math.round(Math.abs(m.pctChange!));
      out.push({
        key: `move-${m.category}`,
        text: `${m.category} is ${pct}% ${m.pctChange! > 0 ? 'above' : 'below'} your recent average (${fmt(m.current)} against ${fmt(m.priorAverage)}).`,
      });
    });

  // "New" only means something when there is history to be new against: with none, every category
  // has no change figure and calling them all new would be the absence of history, not a finding.
  const hasHistory = movers.some((m) => m.pctChange != null);
  if (hasHistory) {
    const newest = movers
      .filter((m) => m.pctChange == null && m.current > 0)
      .sort((a, b) => b.current - a.current)[0];
    if (newest) {
      out.push({
        key: `new-${newest.category}`,
        text: `New: ${newest.category}, ${fmt(newest.current)} in ${monthName}, with nothing in the months before.`,
      });
    }
  }
  return out;
}
