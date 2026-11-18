import { FinoraCard } from '../../design-system';
import type { ComingUpRow, HoldingLike } from '../../lib/investmentSummary';

/** The next deposits to mature and how recurring deposits are going. Hidden when there are none. */
export function ComingUpCard<T extends HoldingLike>({ rows, money }: { rows: ComingUpRow<T>[]; money: (n: number) => string }) {
  if (rows.length === 0) return null;
  return (
    <FinoraCard padding="lg">
      <h2 className="font-display text-base font-extrabold text-ink">Coming up</h2>
      <ul className="mt-3 divide-y divide-border">
        {rows.map((row) => {
          const paidLabel = row.paid !== null ? `${row.paid} of ${row.instalments} paid` : null;
          return (
            <li key={row.holding.id} className="py-3 first:pt-0 last:pb-0">
              {row.dateLabel ? (
                <div className="flex items-center justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-ink">{`${row.holding.name} matures`}</p>
                    <p className="text-[13px] text-muted">{[row.dateLabel, row.whenLabel, paidLabel].filter(Boolean).join(' · ')}</p>
                  </div>
                  {row.amount !== null && <p className="flex-shrink-0 text-sm font-semibold text-ink">{money(row.amount)}</p>}
                </div>
              ) : (
                <>
                  <div className="flex items-center justify-between gap-3">
                    <p className="min-w-0 text-sm font-medium text-ink">{row.holding.name}</p>
                    <p className="flex-shrink-0 text-[13px] text-muted">{paidLabel}</p>
                  </div>
                  <div
                    role="progressbar"
                    aria-label={`${row.holding.name}: ${paidLabel}`}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={Math.round((row.progress ?? 0) * 100)}
                    className="mt-2 h-2 overflow-hidden rounded-full bg-surface"
                  >
                    <div className="h-full rounded-full bg-success" style={{ width: `${(row.progress ?? 0) * 100}%` }} />
                  </div>
                </>
              )}
            </li>
          );
        })}
      </ul>
    </FinoraCard>
  );
}
