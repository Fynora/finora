import { useState } from 'react';
import { Table2 } from 'lucide-react';
import type { AdvancedMonth, AdvancedCategory } from '../../api/endpoints';
import { FinoraCard, SectionHeader, useChartColors } from '../../design-system';
import { fmt } from '../reportSections/reportFormat';
import { compactAmount, monthShort, monthYear } from './advancedFormat';
import { flowTicks, niceCeiling } from './cashFlow';

const WIDTH = 720;
const HEIGHT = 260;
const LEFT = 56;
const RIGHT = 16;
const TOP = 12;
const BOTTOM = 34;
const PLOT_W = WIDTH - LEFT - RIGHT;
const PLOT_H = HEIGHT - TOP - BOTTOM;
/** Lines are told apart by colour AND by dash and marker, so the chart never rests on colour alone. */
const DASHES = [undefined, '6 4', '2 3', '10 3 2 3', '4 2', '1 4'];
const MARKERS = ['circle', 'square', 'diamond', 'triangle', 'circle', 'square'] as const;

function Marker({ x, y, shape, colour }: { x: number; y: number; shape: (typeof MARKERS)[number]; colour: string }) {
  const r = 3.2;
  if (shape === 'square') return <rect x={x - r} y={y - r} width={r * 2} height={r * 2} fill={colour} />;
  if (shape === 'diamond') return <polygon points={`${x},${y - r - 1} ${x + r + 1},${y} ${x},${y + r + 1} ${x - r - 1},${y}`} fill={colour} />;
  if (shape === 'triangle') return <polygon points={`${x},${y - r - 1} ${x + r + 1},${y + r} ${x - r - 1},${y + r}`} fill={colour} />;
  return <circle cx={x} cy={y} r={r} fill={colour} />;
}

/**
 * How the largest categories moved month by month across the period, one line each. The same figures
 * are one click away as a table. Drawn from two months up: one month is not a trend.
 */
export function CategoryTrend({ months, categories }: { months: AdvancedMonth[]; categories: AdvancedCategory[] }) {
  const colours = useChartColors();
  const [count, setCount] = useState(4);
  const [asTable, setAsTable] = useState(false);
  if (months.length < 2 || categories.length === 0) return null;

  const series = categories.slice(0, count);
  const ceiling = niceCeiling(Math.max(...series.flatMap((c) => c.monthly), 0)) || 1;
  const domain = { lo: 0, hi: ceiling };
  const y = (v: number) => TOP + PLOT_H - (v / ceiling) * PLOT_H;
  const x = (i: number) => LEFT + (PLOT_W * i) / (months.length - 1);
  const labelEvery = Math.ceil(months.length / 12);
  const summary = series
    .map((c) => `${c.name}: ${c.monthly.map((v, i) => `${monthShort(months[i].month)} ${fmt(v)}`).join(', ')}`)
    .join('. ');

  return (
    <FinoraCard>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <SectionHeader title="Spending trend by category" />
        <div className="flex items-center gap-2">
          <label className="text-xs text-muted">
            <span className="sr-only">How many categories</span>
            <select
              value={count} onChange={(e) => setCount(Number(e.target.value))}
              className="rounded-lg border border-border bg-card px-2 py-1.5 text-xs text-ink"
            >
              {[3, 4, 6].filter((n) => n <= Math.max(categories.length, 3)).map((n) => <option key={n} value={n}>Top {n} categories</option>)}
            </select>
          </label>
          <button
            type="button" onClick={() => setAsTable((v) => !v)} aria-pressed={asTable}
            className={`inline-flex items-center gap-1.5 rounded-lg border border-border px-2.5 py-1.5 text-xs font-medium ${asTable ? 'bg-primary text-on-primary' : 'text-ink hover:bg-bg'}`}
          >
            <Table2 size={13} aria-hidden="true" /> View as table
          </button>
        </div>
      </div>
      <p className="-mt-2 mb-3 text-xs text-muted">What you spent on each of your largest categories, month by month.</p>

      {asTable ? (
        <div className="overflow-x-auto">
          <table className="w-full text-sm" data-testid="category-trend-table">
            <caption className="sr-only">Spending by category and month</caption>
            <thead>
              <tr className="border-b border-border text-left text-xs text-muted">
                <th scope="col" className="py-2 pr-3 font-medium">Month</th>
                {series.map((c) => <th key={c.name} scope="col" className="py-2 pr-3 text-right font-medium">{c.name}</th>)}
              </tr>
            </thead>
            <tbody>
              {months.map((m, i) => (
                <tr key={m.month} className="border-b border-border last:border-0">
                  <th scope="row" className="py-2 pr-3 text-left font-normal text-ink">{monthYear(m.month)}{m.complete ? '' : '*'}</th>
                  {series.map((c) => <td key={c.name} className="py-2 pr-3 text-right tabular-nums text-ink">{fmt(c.monthly[i])}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={summary} data-testid="category-trend-chart" className="w-full">
          {flowTicks(domain).map((t) => (
            <g key={t}>
              <line x1={LEFT} x2={WIDTH - RIGHT} y1={y(t)} y2={y(t)} className="stroke-border" strokeWidth="1" strokeDasharray={t === 0 ? undefined : '3 4'} />
              <text x={LEFT - 8} y={y(t) + 4} textAnchor="end" fontSize="11" className="fill-muted">{compactAmount(t)}</text>
            </g>
          ))}
          {months.map((m, i) => (i % labelEvery === 0 ? (
            <text key={m.month} x={x(i)} y={HEIGHT - 12} textAnchor="middle" fontSize="11" className="fill-muted">
              {monthShort(m.month)}{m.complete ? '' : '*'}
            </text>
          ) : null))}
          {series.map((c, s) => {
            const colour = colours.series[s % colours.series.length];
            const d = c.monthly.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
            return (
              <g key={c.name} data-testid={`trend-line-${c.name}`}>
                <path d={d} fill="none" stroke={colour} strokeWidth="2" strokeDasharray={DASHES[s % DASHES.length]} strokeLinejoin="round" />
                {c.monthly.map((v, i) => (
                  <Marker key={months[i].month} x={x(i)} y={y(v)} shape={MARKERS[s % MARKERS.length]} colour={colour} />
                ))}
              </g>
            );
          })}
        </svg>
      )}
      <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted" aria-label="Legend">
        {series.map((c, s) => (
          <li key={c.name} className="flex items-center gap-1.5">
            <svg width="26" height="10" aria-hidden="true">
              <line x1="0" x2="26" y1="5" y2="5" stroke={colours.series[s % colours.series.length]} strokeWidth="2" strokeDasharray={DASHES[s % DASHES.length]} />
            </svg>
            {c.name}
          </li>
        ))}
      </ul>
      {months.some((m) => !m.complete) && <p className="mt-1 text-xs text-muted">* Only part of the month is in your data.</p>}
    </FinoraCard>
  );
}
