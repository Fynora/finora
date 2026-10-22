import { Link } from 'react-router-dom';
import { ArrowDownRight, ArrowUpRight, Lightbulb, Minus } from 'lucide-react';
import type { AdvancedInsight } from '../../api/endpoints';
import { FinoraCard, SectionHeader } from '../../design-system';
import { ledgerHref } from './advancedFormat';

const ICON = { UP: ArrowUpRight, DOWN: ArrowDownRight, NEUTRAL: Minus } as const;
const DIRECTION = { UP: 'Up', DOWN: 'Down', NEUTRAL: 'Note' } as const;

/**
 * What stands out in the period, worded by the server so the web and the app say the same thing.
 * Each one carries its figures and the earlier period it is set against, and links to the
 * transactions behind it. The arrow is the direction of the thing described, not a verdict on it,
 * so it is never coloured good or bad.
 */
export function InsightsList({ insights }: { insights: AdvancedInsight[] }) {
  return (
    <FinoraCard>
      <SectionHeader title="Key insights" />
      {insights.length === 0 ? (
        <div className="flex items-start gap-3 text-sm text-muted" data-testid="no-insights">
          <Lightbulb size={16} className="mt-0.5 flex-shrink-0" aria-hidden="true" />
          <p>Nothing stands out yet. Insights appear when there is enough history to compare this period with an earlier one.</p>
        </div>
      ) : (
        <ul className="space-y-3" data-testid="insights">
          {insights.map((i) => {
            const Icon = ICON[i.tone] ?? Minus;
            return (
              <li key={i.id} className="flex gap-3 rounded-xl border border-border p-3">
                <span className="mt-0.5 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-surface text-ink" aria-hidden="true">
                  <Icon size={16} />
                </span>
                <div className="min-w-0">
                  <p className="text-sm font-semibold text-ink"><span className="sr-only">{DIRECTION[i.tone] ?? 'Note'}: </span>{i.headline}</p>
                  <p className="mt-0.5 text-xs text-muted">{i.detail}</p>
                  {i.drill && (
                    <Link to={ledgerHref(i.drill)} className="mt-1 inline-block text-xs font-medium text-primary underline underline-offset-2">
                      See the transactions
                    </Link>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </FinoraCard>
  );
}
