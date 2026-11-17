import type { ReactNode } from 'react';
import type { BudgetGroup } from '../../lib/budgetPace';

/** Ring colour follows the risk group; the same three tones the rows and bars use. */
export const GROUP_TONE: Record<BudgetGroup, string> = {
  over: 'text-danger',
  'heading-over': 'text-warning',
  'on-track': 'text-success',
};

/**
 * Two arcs: the thick outer one is how much of the budget is spent, the thin inner one how much of
 * the month has gone. Decorative for screen readers; the hero around it carries the spoken summary.
 */
export function BudgetRing({
  spentFrac, monthFrac, group, size = 148, children,
}: {
  spentFrac: number; monthFrac: number; group: BudgetGroup; size?: number; children?: ReactNode;
}) {
  const outerStroke = Math.round(size * 0.09);
  const outerR = (size - outerStroke) / 2;
  const innerStroke = 3;
  const innerR = outerR - outerStroke / 2 - 8;
  const arc = (r: number, frac: number) => {
    const c = 2 * Math.PI * r;
    return `${Math.max(0, Math.min(1, frac)) * c} ${c}`;
  };
  const mid = size / 2;
  return (
    <div className="relative flex-shrink-0" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true" className="-rotate-90">
        <circle cx={mid} cy={mid} r={outerR} fill="none" strokeWidth={outerStroke} className="stroke-surface" />
        {spentFrac > 0 && (
          <circle
            data-testid="spent-arc" cx={mid} cy={mid} r={outerR} fill="none" strokeWidth={outerStroke}
            strokeLinecap="round" stroke="currentColor" strokeDasharray={arc(outerR, spentFrac)} className={GROUP_TONE[group]}
          />
        )}
        <circle cx={mid} cy={mid} r={innerR} fill="none" strokeWidth={innerStroke} className="stroke-surface" />
        <circle
          data-testid="month-arc" cx={mid} cy={mid} r={innerR} fill="none" strokeWidth={innerStroke}
          strokeLinecap="round" stroke="currentColor" strokeDasharray={arc(innerR, monthFrac)} className="text-muted"
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">{children}</div>
    </div>
  );
}
