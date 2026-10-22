import { Info } from 'lucide-react';
import type { AdvancedReport } from '../../api/endpoints';
import { FinoraCard } from '../../design-system';
import { coverageNote, gateNote, leftOutNote, monthYear, periodSpan } from './advancedFormat';

/**
 * What the period actually is, and what was left out or withheld, in plain sentences: the months
 * covered, how many of the asked-for months exist, a month left out because it is still in
 * progress, months that are only part of a month, and why there is no comparison. The page never
 * silently mixes periods, so everything that narrows a figure is said here, once.
 */
export function PeriodNotice({ report }: { report: AdvancedReport }) {
  const { period, comparison } = report;
  if (!period.hasData) return null;
  const lines = [
    coverageNote(period),
    leftOutNote(period),
    gateNote(comparison, period),
  ].filter((l): l is string => !!l);
  const compared = comparison.reliable && period.previousStartMonth && period.previousEndMonth
    ? `Compared with ${period.previousStartMonth === period.previousEndMonth ? monthYear(period.previousStartMonth) : `${monthYear(period.previousStartMonth)} – ${monthYear(period.previousEndMonth)}`}.`
    : null;
  return (
    <FinoraCard padding="sm" className="flex items-start gap-3">
      <Info size={16} className="mt-0.5 flex-shrink-0 text-muted" aria-hidden="true" />
      <div className="min-w-0 space-y-0.5 text-xs text-muted" data-testid="period-notice">
        <p className="text-sm text-ink">
          <span className="font-semibold">{periodSpan(period)}</span>
          {' · '}{period.monthCount} {period.monthCount === 1 ? 'month' : 'months'}
          {compared ? ` · ${compared}` : ''}
        </p>
        {lines.map((l) => <p key={l}>{l}</p>)}
      </div>
    </FinoraCard>
  );
}
