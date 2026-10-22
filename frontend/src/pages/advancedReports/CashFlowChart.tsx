import { useState } from 'react';
import { Table2 } from 'lucide-react';
import type { AdvancedMonth } from '../../api/endpoints';
import { FinoraCard, SectionHeader } from '../../design-system';
import { fmt } from '../reportSections/reportFormat';
import { compactAmount } from './advancedFormat';
import {
  flowDomain, flowTicks, monthlyPoints, yearlyPoints, YEARLY_ABOVE_MONTHS, type FlowPoint,
} from './cashFlow';

const WIDTH = 720;
const HEIGHT = 280;
const LEFT = 56;
const RIGHT = 12;
const TOP = 12;
const BOTTOM = 34;
const PLOT_W = WIDTH - LEFT - RIGHT;
const PLOT_H = HEIGHT - TOP - BOTTOM;

/**
 * Income and expenses for each month of the period as paired bars, with net cash flow as a line.
 * A month that is only part of a month is drawn as a dashed outline and starred, because its bars
 * are real but lower than the month's true figures. A period longer than two years is drawn a
 * calendar year at a time, and the toggle says so. "View as table" shows the same figures as text.
 */
export function CashFlowChart({ months, incompleteNoteText }: { months: AdvancedMonth[]; incompleteNoteText: string | null }) {
  // The reader's own choice, or none: what the period suggests is worked out each time, so moving from a
  // short period to a long one draws years even though a short one was on screen before.
  const [chosenGrain, setGrain] = useState<'monthly' | 'yearly' | null>(null);
  const grain = chosenGrain ?? (months.length > YEARLY_ABOVE_MONTHS ? 'yearly' : 'monthly');
  const [asTable, setAsTable] = useState(false);
  const yearly = months.length > 12 && grain === 'yearly';
  const points = yearly ? yearlyPoints(months) : monthlyPoints(months);

  if (months.length === 0) return null;
  const domain = flowDomain(points);
  const span = domain.hi - domain.lo;
  const y = (v: number) => TOP + PLOT_H - ((v - domain.lo) / span) * PLOT_H;
  const zero = y(0);
  const group = PLOT_W / points.length;
  const barW = Math.min(20, group * 0.34);
  const labelEvery = Math.ceil(points.length / 14);
  const cx = (i: number) => LEFT + group * (i + 0.5);
  const netPath = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${cx(i).toFixed(1)},${y(p.net).toFixed(1)}`).join(' ');
  const anyPartial = points.some((p) => !p.complete);
  const summary = points
    .map((p) => `${p.name}${p.complete ? '' : ' (only part is in)'}: income ${fmt(p.income)}, expenses ${fmt(p.expense)}, net ${fmt(p.net)}`)
    .join('. ');

  return (
    <FinoraCard>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <SectionHeader title="Income vs expenses" />
        <div className="flex items-center gap-2">
          {months.length > 12 && (
            <div role="group" aria-label="Chart grain" className="flex rounded-lg border border-border p-0.5 text-xs">
              {(['monthly', 'yearly'] as const).map((g) => (
                <button
                  key={g} type="button" onClick={() => setGrain(g)} aria-pressed={grain === g}
                  className={`rounded-md px-2.5 py-1 font-medium capitalize ${grain === g ? 'bg-primary text-on-primary' : 'text-muted hover:text-ink'}`}
                >
                  {g}
                </button>
              ))}
            </div>
          )}
          <button
            type="button" onClick={() => setAsTable((v) => !v)} aria-pressed={asTable}
            className={`inline-flex items-center gap-1.5 rounded-lg border border-border px-2.5 py-1.5 text-xs font-medium ${asTable ? 'bg-primary text-on-primary' : 'text-ink hover:bg-bg'}`}
          >
            <Table2 size={13} aria-hidden="true" /> View as table
          </button>
        </div>
      </div>
      <p className="-mt-2 mb-3 text-xs text-muted">
        {yearly ? 'Each bar is a calendar year of the period.' : 'Your income and expenses month by month, with the net left over as a line.'}
      </p>

      {asTable ? (
        <div className="overflow-x-auto">
          <table className="w-full text-sm" data-testid="cash-flow-table">
            <caption className="sr-only">Income, expenses and net cash flow {yearly ? 'by year' : 'by month'}</caption>
            <thead>
              <tr className="border-b border-border text-left text-xs text-muted">
                <th scope="col" className="py-2 pr-3 font-medium">{yearly ? 'Year' : 'Month'}</th>
                <th scope="col" className="py-2 pr-3 text-right font-medium">Income</th>
                <th scope="col" className="py-2 pr-3 text-right font-medium">Expenses</th>
                <th scope="col" className="py-2 pr-3 text-right font-medium">Net</th>
                {!yearly && <th scope="col" className="py-2 text-right font-medium">Savings rate</th>}
              </tr>
            </thead>
            <tbody>
              {points.map((p, i) => (
                <tr key={p.key} className="border-b border-border last:border-0">
                  <th scope="row" className="py-2 pr-3 text-left font-normal text-ink">{p.name}{p.complete ? '' : '*'}</th>
                  <td className="py-2 pr-3 text-right tabular-nums text-ink">{fmt(p.income)}</td>
                  <td className="py-2 pr-3 text-right tabular-nums text-ink">{fmt(p.expense)}</td>
                  <td className="py-2 pr-3 text-right tabular-nums text-ink">{fmt(p.net)}</td>
                  {!yearly && (
                    <td className="py-2 text-right tabular-nums text-ink">
                      {months[i].savingsRatePct == null ? '—' : `${Math.round(months[i].savingsRatePct!)}%`}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={summary} data-testid="cash-flow-chart" className="w-full">
          {flowTicks(domain).map((t) => (
            <g key={t}>
              <line x1={LEFT} x2={WIDTH - RIGHT} y1={y(t)} y2={y(t)} className="stroke-border" strokeWidth="1" strokeDasharray={t === 0 ? undefined : '3 4'} />
              <text x={LEFT - 8} y={y(t) + 4} textAnchor="end" fontSize="11" className="fill-muted">{compactAmount(t)}</text>
            </g>
          ))}
          {points.map((p: FlowPoint, i) => {
            const bar = (value: number, tone: 'success' | 'danger', offset: number) => {
              const top = y(Math.max(value, 0));
              const height = Math.max(Math.abs(y(value) - zero), value > 0 ? 1.5 : 0);
              const props = p.complete
                ? { className: tone === 'success' ? 'fill-success' : 'fill-danger' }
                : { className: tone === 'success' ? 'fill-none stroke-success' : 'fill-none stroke-danger', strokeWidth: 1.5, strokeDasharray: '4 3' };
              return (
                <rect x={cx(i) + offset} y={top} width={barW} height={height} rx="2" {...props}>
                  <title>{`${p.name} ${tone === 'success' ? 'income' : 'expenses'} ${fmt(value)}`}</title>
                </rect>
              );
            };
            return (
              <g key={p.key} data-testid={`flow-${p.key}`}>
                {bar(p.income, 'success', -barW - 1)}
                {bar(p.expense, 'danger', 1)}
                {i % labelEvery === 0 && (
                  <text x={cx(i)} y={HEIGHT - 12} textAnchor="middle" fontSize="11" className="fill-muted">
                    {p.label}{p.complete ? '' : '*'}
                  </text>
                )}
              </g>
            );
          })}
          <path d={netPath} fill="none" className="stroke-ink" strokeWidth="2" strokeLinejoin="round" />
          {points.map((p, i) => (
            <circle key={p.key} cx={cx(i)} cy={y(p.net)} r="3" className="fill-card stroke-ink" strokeWidth="1.5">
              <title>{`${p.name} net ${fmt(p.net)}`}</title>
            </circle>
          ))}
        </svg>
      )}

      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
        <span><span className="text-success" aria-hidden="true">●</span> Income</span>
        <span><span className="text-danger" aria-hidden="true">●</span> Expenses</span>
        <span><span className="text-ink" aria-hidden="true">━●</span> Net cash flow</span>
        {anyPartial && <span data-testid="flow-partial-note">* {yearly ? 'Only part of the year is in the period' : 'Only part of the month is in'}, so its bars run lower than the real figures</span>}
      </div>
      {incompleteNoteText && !asTable && <p className="mt-1 text-xs text-muted">{incompleteNoteText}</p>}
    </FinoraCard>
  );
}
