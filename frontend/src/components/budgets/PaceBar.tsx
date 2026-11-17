import type { BudgetGroup } from '../../lib/budgetPace';

const FILL: Record<BudgetGroup, string> = {
  over: 'bg-danger',
  'heading-over': 'bg-warning',
  'on-track': 'bg-success',
};

/**
 * Spent bar with a pace marker: the marker sits where spending would be today if the budget were
 * spread evenly over the month, so a fill past the marker means "ahead of pace".
 */
export function PaceBar({ spentFrac, monthFrac, group, className = '' }: {
  spentFrac: number; monthFrac: number; group: BudgetGroup; className?: string;
}) {
  const label = `Spent ${Math.round(spentFrac * 100)}%, month ${Math.round(monthFrac * 100)}% gone`;
  return (
    <div role="img" aria-label={label} className={`relative h-2 rounded-full bg-surface ${className}`}>
      {spentFrac > 0 && (
        <div className={`h-full rounded-full ${FILL[group]}`} style={{ width: `${Math.min(100, spentFrac * 100)}%` }} />
      )}
      <div
        aria-hidden="true"
        data-testid="pace-marker"
        className="absolute -top-1 h-4 w-0.5 -translate-x-1/2 rounded-full bg-ink"
        style={{ left: `${monthFrac * 100}%` }}
      />
    </div>
  );
}
