import { FinoraCard } from '../../design-system';
import { holdingTerms, shareLabel, type PortfolioSummary } from '../../lib/investmentSummary';
import type { Account } from '../../types';
import { RowMenu, type RowMenuItem } from '../budgets/RowMenu';
import { GROUP_COLOR } from './groupColors';

/** Every holding, grouped by type, largest first inside each group. */
export function HoldingsCard({ summary, today, money, onUpdate, onDelete, busyId }: {
  summary: PortfolioSummary<Account>;
  today: Date;
  money: (n: number) => string;
  onUpdate: (holding: Account) => void;
  onDelete: (holding: Account) => void;
  /** The holding being deleted: its row is locked so it can't be deleted twice. */
  busyId: string | null;
}) {
  return (
    <FinoraCard padding="lg">
      <h2 className="font-display text-base font-extrabold text-ink">Holdings</h2>
      {summary.groups.map((group) => (
        <section key={group.key} aria-label={group.label}>
          <div className="flex items-center justify-between gap-3 pb-2 pt-4">
            <h3 className="flex items-center gap-2 text-[13px] font-semibold text-ink">
              <span aria-hidden="true" className="h-2 w-2 rounded-full" style={{ backgroundColor: GROUP_COLOR[group.key] }} />
              {group.label}
            </h3>
            <p className="text-[13px] text-muted">{`${money(group.total)} · ${shareLabel(group.share)}`}</p>
          </div>
          {group.items.map(({ holding, share }) => {
            const terms = holdingTerms(holding, today, money);
            const percent = shareLabel(share);
            // A bank-linked holding's value comes from its link; a typed one would be overwritten.
            const items: RowMenuItem[] = [
              ...(holding.primarySource === 'ACCOUNT_AGGREGATOR' ? [] : [{ label: 'Update value', onSelect: () => onUpdate(holding) }]),
              { label: 'Delete holding', onSelect: () => onDelete(holding), danger: true },
            ];
            return (
              <div key={holding.id} className={`flex items-center gap-3 border-t border-border py-3 md:gap-4 ${busyId === holding.id ? 'opacity-60' : ''}`}>
                <div
                  role="group"
                  aria-label={`${holding.name}: ${money(holding.balance)}, ${percent} of your portfolio. ${terms}.`}
                  className="flex min-w-0 flex-1 items-center gap-3 md:gap-4"
                >
                  <div className="min-w-0 flex-1">
                    <p className="break-words text-sm font-medium text-ink">{holding.name}</p>
                    <p className="break-words text-[13px] text-muted">{terms}</p>
                  </div>
                  <div className="hidden w-40 flex-shrink-0 items-center gap-2.5 md:flex">
                    <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface">
                      <div
                        data-testid="holding-share-bar"
                        className="h-full rounded-full"
                        style={{ width: `${Math.max(0, Math.min(100, share * 100))}%`, backgroundColor: GROUP_COLOR[group.key] }}
                      />
                    </div>
                    <span className="w-9 text-right text-[13px] text-muted">{percent}</span>
                  </div>
                  <p className="flex-shrink-0 text-right text-sm font-semibold text-ink md:w-28">{money(holding.balance)}</p>
                </div>
                <RowMenu label={`More actions for ${holding.name}`} items={items} disabled={busyId === holding.id} />
              </div>
            );
          })}
        </section>
      ))}
    </FinoraCard>
  );
}
