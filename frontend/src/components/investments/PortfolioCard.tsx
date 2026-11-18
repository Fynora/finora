import { FinoraCard } from '../../design-system';
import { shareLabel, type HoldingLike, type PortfolioSummary } from '../../lib/investmentSummary';
import { GROUP_COLOR } from './groupColors';

/** The total, and how it is spread across types of holding. */
export function PortfolioCard<T extends HoldingLike>({ summary, money }: { summary: PortfolioSummary<T>; money: (n: number) => string }) {
  const mixLabel = summary.mix.map((m) => `${m.label} ${shareLabel(m.share)}`).join(', ');
  return (
    <FinoraCard padding="lg">
      <p className="text-[13px] font-medium text-muted">Total value</p>
      <p className="mt-1 font-display text-3xl font-extrabold tracking-tight text-ink md:text-4xl">{money(summary.total)}</p>
      {summary.mix.length > 0 && (
        <>
          <div role="img" aria-label={mixLabel} className="mt-4 flex h-2.5 gap-0.5 overflow-hidden rounded-full">
            {summary.mix.map((m) => (
              <div key={m.key} style={{ width: `${m.share * 100}%`, backgroundColor: GROUP_COLOR[m.key] }} />
            ))}
          </div>
          <ul className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:flex xl:justify-between">
            {summary.mix.map((m) => (
              <li key={m.key} className="flex items-start gap-2">
                <span aria-hidden="true" className="mt-1.5 h-2 w-2 flex-shrink-0 rounded-full" style={{ backgroundColor: GROUP_COLOR[m.key] }} />
                <span className="min-w-0">
                  <span className="block text-[13px] font-medium text-ink">{m.label}</span>
                  <span className="block text-[13px] text-muted">{`${money(m.total)} · ${shareLabel(m.share)}`}</span>
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </FinoraCard>
  );
}
