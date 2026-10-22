import type { ReactNode } from 'react';
import { ArrowDownCircle, ArrowUpCircle, CalendarDays, PiggyBank, Wallet } from 'lucide-react';
import type { AdvancedReport } from '../../api/endpoints';
import { FinoraCard } from '../../design-system';
import { fmt } from '../reportSections/reportFormat';
import { againstName, percentChange, pointsChange } from './advancedFormat';

type Tone = 'good' | 'bad' | 'plain';

/** The change line under a figure. The arrow and the words carry direction; colour only adds to them. */
function Change({ text, tone, against }: { text: string | null; tone: Tone; against: string }) {
  if (!text) return null;
  // "No change" is neither good nor bad: it is never coloured.
  const colour = text === 'No change' ? 'text-ink' : tone === 'good' ? 'text-success' : tone === 'bad' ? 'text-danger' : 'text-ink';
  return (
    <p className="mt-1 text-xs text-muted">
      <span className={`font-semibold tabular-nums ${colour}`}>{text}</span> vs {against}
    </p>
  );
}

function Kpi({
  label, value, valueColour, icon: Icon, iconBg, iconColour, children, testId,
}: {
  label: string; value: string; valueColour?: string; icon: typeof Wallet; iconBg: string; iconColour: string;
  children?: ReactNode; testId: string;
}) {
  return (
    <FinoraCard padding="sm" className="min-w-0">
      <div className="flex items-start justify-between gap-2">
        <p className="text-xs font-semibold text-muted">{label}</p>
        <span className={`flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg ${iconBg}`} aria-hidden="true">
          <Icon size={16} className={iconColour} />
        </span>
      </div>
      <p data-testid={testId} className={`mt-2 truncate font-display text-xl font-extrabold tabular-nums tracking-tight ${valueColour ?? 'text-ink'}`}>{value}</p>
      {children}
    </FinoraCard>
  );
}

const RATE_WITHHELD: Record<string, string> = {
  NO_INCOME: 'No income counted in this period',
  UNRESOLVED_EXCEEDS_INCOME: 'Held back: more credits are unclassified than counted as income',
};

/**
 * Income, expenses, net cash flow, average monthly spend and savings rate for the period, each with
 * how it moved against the period it is compared with. Every figure and every change is the
 * server's. When the server withheld the comparison the change lines are left off and the page says
 * why once, in the period notice, rather than five times here.
 */
export function KpiRow({ report }: { report: AdvancedReport }) {
  const { totals, comparison: c, period } = report;
  const against = againstName(period);
  const ok = c.reliable;
  const rate = totals.savingsRatePct;
  const rateDelta = ok ? pointsChange(c.savingsRateDeltaPp) : null;

  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-5" data-testid="kpi-row">
      <Kpi
        testId="kpi-income" label="Total income" value={fmt(totals.income)} valueColour="text-success"
        icon={ArrowDownCircle} iconBg="bg-success-bg" iconColour="text-success"
      >
        <Change text={ok ? percentChange(c.incomeDeltaPct) : null} tone={(c.incomeDeltaPct ?? 0) >= 0 ? 'good' : 'bad'} against={against} />
      </Kpi>
      <Kpi
        testId="kpi-expense" label="Total expenses" value={fmt(totals.expense)} valueColour="text-danger"
        icon={ArrowUpCircle} iconBg="bg-danger-bg" iconColour="text-danger"
      >
        <Change text={ok ? percentChange(c.expenseDeltaPct) : null} tone={(c.expenseDeltaPct ?? 0) > 0 ? 'bad' : 'good'} against={against} />
      </Kpi>
      <Kpi
        testId="kpi-net" label="Net cash flow" value={fmt(totals.net)} valueColour={totals.net >= 0 ? 'text-success' : 'text-danger'}
        icon={Wallet} iconBg="bg-surface" iconColour="text-ink"
      >
        {/* Net is compared as an amount: a percent of a negative or near-zero net misleads. */}
        <Change
          text={ok && c.netDelta != null ? (Math.round(c.netDelta) === 0 ? 'No change' : `${c.netDelta > 0 ? '↑' : '↓'} ${fmt(Math.abs(c.netDelta))}`) : null}
          tone={(c.netDelta ?? 0) >= 0 ? 'good' : 'bad'} against={against}
        />
      </Kpi>
      <Kpi
        testId="kpi-average" label="Average monthly spend"
        value={totals.avgMonthlyExpense == null ? '—' : fmt(totals.avgMonthlyExpense)}
        icon={CalendarDays} iconBg="bg-surface" iconColour="text-ink"
      >
        <Change text={ok ? percentChange(c.avgMonthlyExpenseDeltaPct) : null} tone={(c.avgMonthlyExpenseDeltaPct ?? 0) > 0 ? 'bad' : 'good'} against={against} />
        {totals.monthsWithData > 0 && (
          <p className="mt-1 text-2xs text-muted">Over {totals.monthsWithData} {totals.monthsWithData === 1 ? 'month' : 'months'} with transactions</p>
        )}
      </Kpi>
      <Kpi
        testId="kpi-savings" label="Savings rate" value={rate == null ? '—' : `${Math.round(rate)}%`}
        icon={PiggyBank} iconBg="bg-surface" iconColour="text-ink"
      >
        {rate == null ? (
          <p className="mt-1 text-2xs text-muted">{RATE_WITHHELD[totals.savingsRateGateReason ?? ''] ?? 'Not enough to work out a rate'}</p>
        ) : (
          <Change text={rateDelta} tone={(c.savingsRateDeltaPp ?? 0) >= 0 ? 'good' : 'bad'} against={against} />
        )}
      </Kpi>
    </div>
  );
}
