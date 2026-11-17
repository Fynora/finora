import type { ReactNode } from 'react';
import { FinoraCard } from '../../design-system';
import type { BudgetPace, MonthClock } from '../../lib/budgetPace';
import { BudgetRing } from './BudgetRing';

type Money = (n: number) => string;

function Figure({ label, children, sub, subClass = 'text-muted' }: { label: string; children: ReactNode; sub: string; subClass?: string }) {
  return (
    <div className="min-w-0">
      <p className="text-[13px] font-medium text-muted">{label}</p>
      <div className="mt-1.5 flex items-baseline gap-1">{children}</div>
      <p className={`mt-1.5 text-[13px] ${subClass}`}>{sub}</p>
    </div>
  );
}

/** The month at a glance: spent vs total, safe to spend per day, and where this pace lands. */
export function BudgetHero({
  totalSpent, totalLimit, overall, clock, money,
}: {
  totalSpent: number; totalLimit: number; overall: BudgetPace; clock: MonthClock; money: Money;
}) {
  const spentPct = Math.round(overall.spentFrac * 100);
  const monthPct = Math.round(clock.monthFrac * 100);
  const lastDay = clock.daysLeft === 0;
  const isOver = overall.group === 'over';
  const forecastOver = overall.forecastOverBy > 0;

  const daily = isOver
    ? `${money(overall.overBy)} over your budgets.`
    : lastDay
      ? `Last day, ${money(overall.remaining)} left.`
      : `${money(overall.safePerDay)} a day safe for ${clock.daysLeft} days.`;
  const summary = `${money(totalSpent)} spent of ${money(totalLimit)}. ${daily} At this pace ${money(overall.forecast)} by ${clock.monthEndLabel}.`;

  const figure = 'font-display text-[28px] font-extrabold tracking-tight text-ink';
  return (
    <FinoraCard padding="lg">
      <div role="group" aria-label={summary} className="flex flex-col items-center gap-8 sm:flex-row">
        <BudgetRing spentFrac={overall.spentFrac} monthFrac={clock.monthFrac} group={overall.group}>
          <span aria-hidden="true" className="font-display text-[26px] font-extrabold text-ink">{spentPct}%</span>
          <span aria-hidden="true" className="text-xs text-muted">spent</span>
        </BudgetRing>
        <div aria-hidden="true" className="grid w-full flex-1 grid-cols-1 gap-6 sm:grid-cols-3">
          <Figure label={`Spent of ${money(totalLimit)}`} sub={`${monthPct}% of the month gone, ${spentPct}% of budget used`}>
            <span className={figure}>{money(totalSpent)}</span>
          </Figure>
          <Figure label="Safe to spend" sub={lastDay ? 'Across all budgets' : `${clock.daysLeft} days left · across all budgets`}>
            {lastDay && !isOver ? (
              <span className="font-display text-xl font-extrabold text-ink">{`Last day · ${money(overall.remaining)} left`}</span>
            ) : (
              <>
                <span className={figure}>{money(overall.safePerDay)}</span>
                <span className="text-sm text-muted">/ day</span>
              </>
            )}
          </Figure>
          <Figure
            label={`At this pace by ${clock.monthEndLabel}`}
            sub={forecastOver ? `${money(overall.forecastOverBy)} over your budgets` : `${money(totalLimit - overall.forecast)} under your budgets`}
            subClass={forecastOver ? 'text-danger' : 'text-muted'}
          >
            <span className={`font-display text-[28px] font-extrabold tracking-tight ${forecastOver ? 'text-danger' : 'text-ink'}`}>
              {money(overall.forecast)}
            </span>
          </Figure>
        </div>
      </div>
    </FinoraCard>
  );
}
