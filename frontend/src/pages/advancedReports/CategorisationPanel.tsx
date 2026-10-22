import { useQuery } from '@tanstack/react-query';
import { Brain } from 'lucide-react';
import { analyticsApi } from '../../api/endpoints';
import { EmptyState, FinoraCard, SectionHeader, Skeleton } from '../../design-system';
import { monthYear } from './advancedFormat';

/**
 * How well Fynora knows your merchants. This describes the categorisation engine, not your money,
 * and it does not follow the period picker: what the engine has learned is its whole history. Fetched
 * only when this tab is open. Confidence is an average over merchants and each row says how many
 * merchants it rests on, so a category backed by one merchant is not read as equally sure as one
 * backed by forty. Learned means Fynora filed it on its own; corrected means you changed it.
 */
export function CategorisationPanel() {
  const confidenceQ = useQuery({ queryKey: ['advanced-reports-confidence'], queryFn: () => analyticsApi.categoryConfidence() });
  const learningQ = useQuery({ queryKey: ['advanced-reports-learning-growth'], queryFn: () => analyticsApi.learningGrowth() });
  const confidence = confidenceQ.data ?? [];
  const learning = learningQ.data ?? [];
  const peak = Math.max(...learning.map((p) => p.learnedCount + p.correctedCount), 1);

  return (
    <div className="space-y-6">
      <p className="text-sm text-muted" data-testid="categorisation-intro">
        This is about how Fynora sorts your transactions, not about your money, and it does not change with the period above: it covers everything it has learned from you.
      </p>
      <div className="grid gap-6 lg:grid-cols-2">
        <FinoraCard>
          <SectionHeader title="How sure Fynora is" />
          <p className="-mt-2 mb-3 text-xs text-muted">The average confidence across the merchants filed under each category.</p>
          {confidenceQ.isLoading ? (
            <Skeleton.Region label="Loading confidence"><Skeleton.Block className="h-40 w-full" /></Skeleton.Region>
          ) : confidenceQ.isError && confidenceQ.data === undefined ? (
            <p className="text-sm text-muted">Couldn’t load this just now.</p>
          ) : confidence.length === 0 ? (
            <EmptyState icon={Brain} iconBg="bg-surface" iconColor="text-ink" title="Nothing learned yet" desc="Confirm a few categorisations and this fills in." />
          ) : (
            <ul className="space-y-3" data-testid="confidence-rows">
              {confidence.map((c) => (
                <li key={c.category}>
                  <div className="flex items-baseline justify-between gap-3 text-sm">
                    <span className="min-w-0 truncate text-ink">{c.category}</span>
                    <span className="flex-shrink-0 tabular-nums text-ink">{Math.round(c.avgConfidence)}%</span>
                  </div>
                  <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-border" aria-hidden="true">
                    <div className="h-full rounded-full bg-primary" style={{ width: `${Math.min(100, Math.max(0, c.avgConfidence))}%` }} />
                  </div>
                  <p className="mt-0.5 text-2xs text-muted">Across {c.merchantCount} {c.merchantCount === 1 ? 'merchant' : 'merchants'}</p>
                </li>
              ))}
            </ul>
          )}
        </FinoraCard>

        <FinoraCard>
          <SectionHeader title="Learned and corrected" />
          <p className="-mt-2 mb-3 text-xs text-muted">Each month: categorisations Fynora learned on its own, and ones you corrected.</p>
          {learningQ.isLoading ? (
            <Skeleton.Region label="Loading learning history"><Skeleton.Block className="h-40 w-full" /></Skeleton.Region>
          ) : learningQ.isError && learningQ.data === undefined ? (
            <p className="text-sm text-muted">Couldn’t load this just now.</p>
          ) : learning.length === 0 ? (
            <EmptyState icon={Brain} iconBg="bg-surface" iconColor="text-ink" title="No learning history yet" desc="This fills in as you confirm categorisations over time." />
          ) : (
            <ul className="space-y-2" data-testid="learning-rows">
              {learning.map((p) => (
                <li key={p.month} className="grid grid-cols-[4.5rem_1fr_auto] items-center gap-3 text-sm">
                  <span className="text-muted">{monthYear(p.month)}</span>
                  <span className="flex h-2 overflow-hidden rounded-full bg-border" aria-hidden="true">
                    <span className="h-full bg-success" style={{ width: `${(p.learnedCount / peak) * 100}%` }} />
                    <span className="h-full bg-primary" style={{ width: `${(p.correctedCount / peak) * 100}%` }} />
                  </span>
                  <span className="text-xs tabular-nums text-ink">{p.learnedCount} learned · {p.correctedCount} corrected</span>
                </li>
              ))}
            </ul>
          )}
        </FinoraCard>
      </div>
    </div>
  );
}
