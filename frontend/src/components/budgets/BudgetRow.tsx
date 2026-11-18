import { useEffect, useRef, useState, type MutableRefObject } from 'react';
import { Wallet } from 'lucide-react';
import { LimitEditor } from './LimitEditor';
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
  budget, pace, clock, today, category, money, onSaveLimit, onRemove, removing, focusRequest,
}: {
  budget: Budget;
  pace: BudgetPace;
  clock: MonthClock;
  today: Date;
  category?: CategoryOption;
  money: Money;
  /** Saves a new limit; rejects with the server's error so the editor can show it and stay open. */
  onSaveLimit?: (limit: number) => Promise<void>;
  onRemove: () => void;
  removing: boolean;
  /** Budget id whose limit should take focus once its editor closes. Held by the page, because a
   *  save can move a row to another group, which mounts it afresh. */
  focusRequest?: MutableRefObject<string | null>;
}) {
  const [editing, setEditing] = useState(false);
  const limitButton = useRef<HTMLButtonElement>(null);
  const ownFocusRequest = useRef<string | null>(null);
  const focusAfterEdit = focusRequest ?? ownFocusRequest;
  useEffect(() => {
    if (editing || focusAfterEdit.current !== budget.id) return;
    focusAfterEdit.current = null;
    limitButton.current?.focus();
  });
  const Icon = ICON_COMPONENTS[category?.icon ?? 'tag'] ?? Wallet;
  const color = COLOR_HEX[category?.color ?? 'gray'];
  const copy = describePace(pace, clock, money);
  const items: RowMenuItem[] = [
    ...(onSaveLimit ? [{ label: 'Edit limit', onSelect: () => setEditing(true) }] : []),
    { label: 'See transactions', to: transactionsHref(budget.categoryName, today, clock) },
    { label: 'Remove budget', onSelect: onRemove, danger: true },
  ];

  const icon = (
    <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full" style={{ backgroundColor: `${color}26` }}>
      <Icon size={16} style={{ color }} aria-hidden="true" />
    </div>
  );
  // On a phone the name keeps the first line to itself (the icon and its gap are 3rem): beside a
  // seven-figure amount it was cut to a single letter.
  const name = (
    <span data-testid="budget-name" className="min-w-0 flex-1 truncate font-medium text-ink max-sm:basis-[calc(100%-3rem)] xl:w-40 xl:flex-none">
      {budget.categoryName}
    </span>
  );

  if (editing && onSaveLimit) {
    return (
      <div data-testid="budget-row" className="flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-border py-3 text-sm md:flex-nowrap">
        {icon}
        {name}
        <LimitEditor
          categoryName={budget.categoryName}
          initial={budget.monthlyLimit}
          onSave={async (limit) => {
            focusAfterEdit.current = budget.id;
            try {
              await onSaveLimit(limit);
            } catch (e) {
              focusAfterEdit.current = null;
              throw e;
            }
            setEditing(false);
          }}
          onCancel={() => {
            focusAfterEdit.current = budget.id;
            setEditing(false);
          }}
        />
      </div>
    );
  }

  return (
    <div
      data-testid="budget-row"
      aria-busy={removing || undefined}
      className={`flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-border py-3.5 text-sm xl:flex-nowrap ${removing ? 'opacity-60' : ''}`}
    >
      {icon}
      {name}
      <PaceBar
        spentFrac={pace.spentFrac}
        monthFrac={clock.monthFrac}
        group={pace.group}
        className="order-last w-full xl:order-none xl:w-auto xl:flex-1"
      />
      <span className="flex-shrink-0 text-right max-sm:min-w-0 max-sm:flex-1 max-sm:text-left xl:w-40">
        <span className="font-semibold text-ink">{money(budget.spentThisMonth)}</span>
        <span className="text-muted"> / </span>
        {onSaveLimit ? (
          <button
            ref={limitButton}
            type="button"
            disabled={removing}
            onClick={() => setEditing(true)}
            aria-label={`Edit the ${budget.categoryName} limit, now ${money(budget.monthlyLimit)}`}
            title="Edit limit"
            className="rounded text-muted underline decoration-dotted underline-offset-4 hover:text-ink disabled:cursor-not-allowed"
          >
            {money(budget.monthlyLimit)}
          </button>
        ) : (
          <span className="text-muted">{money(budget.monthlyLimit)}</span>
        )}
      </span>
      {/* Below xl the bar and this status drop to their own full-width lines (both order-last, in
          DOM order); from xl up everything sits on one row as in the Figma frame. */}
      <div className="order-last w-full min-w-0 xl:order-none xl:w-52">
        <p className={`font-semibold ${pace.group === 'over' ? 'text-danger' : 'text-ink'}`}>{copy.main}</p>
        {copy.sub && <p className={`text-xs ${pace.group === 'heading-over' ? 'text-warning' : 'text-muted'}`}>{copy.sub}</p>}
      </div>
      <RowMenu label={`More actions for ${budget.categoryName}`} items={items} disabled={removing} />
    </div>
  );
}
