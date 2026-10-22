import { Wallet } from 'lucide-react';
import type { AdvancedReport } from '../../api/endpoints';
import { EmptyState, FinoraCard, SectionHeader } from '../../design-system';
import { fmt } from '../reportSections/reportFormat';
import { againstName, monthYear } from './advancedFormat';
import { ChangeLine } from './ChangeLine';

const share = (pct: number) => (pct < 1 ? '<1%' : `${Math.round(pct)}%`);

/**
 * Income in the period: where it came from, month by month, and how steady it was. Steadiness is
 * described, not scored: how many months had income, and how the lowest whole month compares with
 * the highest. Only credits Fynora counts as income are here; credits it cannot yet call income are
 * named in a line of their own, never folded in.
 */
export function IncomePanel({ report }: { report: AdvancedReport }) {
  const { income, months, totals, period } = report;
  const against = againstName(period);
  const maxMonth = Math.max(...months.map((m) => m.income), 1);

  if (totals.income <= 0 && totals.unresolvedInflow <= 0) {
    return (
      <FinoraCard>
        <SectionHeader title="Income" />
        <EmptyState icon={Wallet} iconBg="bg-surface" iconColor="text-ink" title="No income in this period" desc="Credits that Fynora counts as income appear here, by source and by month." />
      </FinoraCard>
    );
  }

  return (
    <div className="grid items-start gap-6 lg:grid-cols-2">
      <FinoraCard>
        <SectionHeader title="Income by source" />
        {income.sources.length === 0 ? (
          <p className="text-sm text-muted">No credit in this period is counted as income yet.</p>
        ) : (
          <ul className="space-y-3" data-testid="income-sources">
            {income.sources.map((s) => (
              <li key={s.label}>
                <div className="flex items-baseline justify-between gap-3 text-sm">
                  <span className="min-w-0 truncate text-ink">{s.label}</span>
                  <span className="flex-shrink-0 tabular-nums text-ink">{fmt(s.amount)} <span className="text-xs text-muted">{share(s.sharePct)}</span></span>
                </div>
                <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-border" aria-hidden="true">
                  <div className="h-full rounded-full bg-success" style={{ width: `${s.sharePct}%` }} />
                </div>
                {s.previousAmount != null && (
                  <p className="mt-1 text-2xs text-muted">
                    {s.previousAmount === 0 ? `New, nothing in ${against}` : `${fmt(s.previousAmount)} in ${against}`}
                    {s.previousAmount > 0 && <> · <ChangeLine previousAmount={s.previousAmount} changeAmount={s.amount - s.previousAmount} changePct={((s.amount - s.previousAmount) / s.previousAmount) * 100} /></>}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
        {totals.unresolvedInflow > 0 && (
          <p className="mt-4 text-xs text-muted" data-testid="unresolved-inflow">
            {fmt(totals.unresolvedInflow)} in {totals.unresolvedInflowCount} {totals.unresolvedInflowCount === 1 ? 'credit is' : 'credits are'} not counted as income yet, because Fynora cannot tell what {totals.unresolvedInflowCount === 1 ? 'it was' : 'they were'}. Classifying {totals.unresolvedInflowCount === 1 ? 'it' : 'them'} in Review changes these figures.
          </p>
        )}
      </FinoraCard>

      <FinoraCard>
        <SectionHeader title="Income by month" />
        <ul className="space-y-2" data-testid="income-months">
          {months.map((m) => (
            <li key={m.month} className="grid grid-cols-[4.5rem_1fr_auto] items-center gap-3 text-sm">
              <span className="text-muted">{monthYear(m.month)}{m.complete ? '' : '*'}</span>
              <span className="h-1.5 overflow-hidden rounded-full bg-border" aria-hidden="true">
                <span className="block h-full rounded-full bg-success" style={{ width: `${(m.income / maxMonth) * 100}%` }} />
              </span>
              <span className="tabular-nums text-ink">{m.income > 0 ? fmt(m.income) : '—'}</span>
            </li>
          ))}
        </ul>
        {months.some((m) => !m.complete) && <p className="mt-2 text-xs text-muted">* Only part of the month is in your data.</p>}
        <p className="mt-4 text-xs text-muted" data-testid="income-steadiness">
          Income came in during {income.monthsWithIncome} of {months.length} {months.length === 1 ? 'month' : 'months'}.
          {income.highestMonth && income.lowestMonth && income.lowestAsPctOfHighest != null && (
            <> Among whole months the highest was {monthYear(income.highestMonth)} ({fmt(income.highestMonthAmount ?? 0)}) and the lowest {monthYear(income.lowestMonth)} ({fmt(income.lowestMonthAmount ?? 0)}), {Math.round(income.lowestAsPctOfHighest)}% of the highest.</>
          )}
        </p>
      </FinoraCard>
    </div>
  );
}
