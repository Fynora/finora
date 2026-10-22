import { fmt } from '../reportSections/reportFormat';
import { percentChange, signedAmount } from './advancedFormat';

/**
 * How one row of spending moved against the earlier period: "+₹5,000 · ↑ 12%", "New" when it had
 * nothing then, "No change" when it rounds to none. Renders nothing when the server withheld the
 * comparison (`previousAmount` is null), so a row never shows a change that was not worked out.
 * More spent reads in the danger colour and less in the success colour, but the sign and arrow say
 * it too.
 */
export function ChangeLine({
  previousAmount, changeAmount, changePct,
}: { previousAmount: number | null; changeAmount: number | null; changePct: number | null }) {
  if (previousAmount == null || changeAmount == null) return null;
  if (previousAmount === 0) {
    return changeAmount === 0 ? null : <span className="text-xs text-muted">New, nothing in the earlier period</span>;
  }
  const rounded = Math.round(changeAmount);
  if (rounded === 0) return <span className="text-xs text-muted">No change</span>;
  const pct = percentChange(changePct);
  return (
    <span className={`text-xs font-medium tabular-nums ${rounded > 0 ? 'text-danger' : 'text-success'}`}>
      {signedAmount(rounded, fmt)}{pct ? ` · ${pct}` : ''}
    </span>
  );
}
