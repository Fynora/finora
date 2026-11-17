import { Wallet } from 'lucide-react';
import type { CategoryOption } from '../../api/endpoints';
import type { Budget } from '../../types';
import { describePace, type BudgetPace, type MonthClock } from '../../lib/budgetPace';
import { COLOR_HEX, ICON_COMPONENTS } from '../../lib/categoryIcons';
import { PaceBar } from './PaceBar';
import { RowMenu, type RowMenuItem } from './RowMenu';

type Money = (n: number) => string;

function pad(n: number) {
  return String(n).padStart(2, '0');
}

/** The Ledger link Reports rows already use: category by name, this calendar month. */
export function transactionsHref(categoryName: string, today: Date, clock: MonthClock) {
  const ym = `${today.getFullYear()}-${pad(today.getMonth() + 1)}`;
  return `/app/transactions?category=${encodeURIComponent(categoryName)}&from=${ym}-01&to=${ym}-${pad(clock.daysInMonth)}`;
}

export function BudgetRow({
  budget, pace, clock, today, category, money, onEdit, onRemove, removing,
}: {
  budget: Budget;
  pace: BudgetPace;
  clock: MonthClock;
  today: Date;
  category?: CategoryOption;
  money: Money;
  onEdit?: () => void;
  onRemove: () => void;
  removing: boolean;
}) {
  const Icon = ICON_COMPONENTS[category?.icon ?? 'tag'] ?? Wallet;
  const color = COLOR_HEX[category?.color ?? 'gray'];
  const copy = describePace(pace, clock, money);
  const items: RowMenuItem[] = [
    ...(onEdit ? [{ label: 'Edit limit', onSelect: onEdit }] : []),
    { label: 'See transactions', to: transactionsHref(budget.categoryName, today, clock) },
    { label: 'Remove budget', onSelect: onRemove, danger: true },
  ];

  return (
    <div
      data-testid="budget-row"
      aria-busy={removing || undefined}
      className={`flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-border py-3.5 text-sm md:flex-nowrap ${removing ? 'opacity-60' : ''}`}
    >
      <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full" style={{ backgroundColor: `${color}26` }}>
        <Icon size={16} style={{ color }} aria-hidden="true" />
      </div>
      <span data-testid="budget-name" className="min-w-0 flex-1 truncate font-medium text-ink md:w-40 md:flex-none">
        {budget.categoryName}
      </span>
      <PaceBar
        spentFrac={pace.spentFrac}
        monthFrac={clock.monthFrac}
        group={pace.group}
        className="order-last w-full md:order-none md:w-auto md:flex-1"
      />
      <span className="flex-shrink-0 text-right md:w-40">
        <span className="font-semibold text-ink">{money(budget.spentThisMonth)}</span>
        <span className="text-muted"> / </span>
        <span className="text-muted underline decoration-dotted underline-offset-4">{money(budget.monthlyLimit)}</span>
      </span>
      {/* Below md the bar and this status drop to their own full-width lines (both order-last, in
          DOM order); from md up everything sits on one row as in the Figma frame. */}
      <div className="order-last w-full min-w-0 md:order-none md:w-52">
        <p className={`font-semibold ${pace.group === 'over' ? 'text-danger' : 'text-ink'}`}>{copy.main}</p>
        {copy.sub && <p className={`text-xs ${pace.group === 'heading-over' ? 'text-warning' : 'text-muted'}`}>{copy.sub}</p>}
      </div>
      <RowMenu label={`More actions for ${budget.categoryName}`} items={items} />
    </div>
  );
}
