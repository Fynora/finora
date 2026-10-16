import type { ReportTrendPoint } from '../../api/endpoints';
import { FinoraCard, SectionHeader } from '../../design-system';
import { fmt } from './reportFormat';
import { averageSpend, shortMonth, trendCeiling } from './trend';

const WIDTH = 640;
const HEIGHT = 168;
const BASELINE = 134;
const TOP = 16;
const PLOT_HEIGHT = BASELINE - TOP;
const BAR_WIDTH = 20;
const SIDE_PAD = 20;

/** A bar's height, never rounding a real amount down to nothing. */
const barHeight = (value: number, ceiling: number) => (value > 0 ? Math.max((value / ceiling) * PLOT_HEIGHT, 1.5) : 0);

/**
 * Income and expenses for the last months, as paired bars, with a dashed line at the average spend
 * of the months that are whole. A month that is only partly in the user's data is drawn as a dashed
 * outline and starred, because its bars are real but lower than the month's true totals; it is
 * still shown, not dropped. Drawn from two months up: one month is not a trend.
 */
export function TrendPanel({ points, selectedMonth }: { points: ReportTrendPoint[] | null; selectedMonth: string }) {
  if (!points || points.length < 2) return null;
  const ceiling = trendCeiling(points);
  const average = averageSpend(points);
  const groupWidth = (WIDTH - SIDE_PAD * 2) / points.length;
  const anyPartial = points.some((p) => !p.complete);
  const summary = points
    .map((p) => `${shortMonth(p.month)}${p.complete ? '' : ' (only part of the month is in)'}: income ${fmt(p.income)}, expenses ${fmt(p.expense)}`)
    .join('. ');

  return (
    <FinoraCard>
      <SectionHeader title={`Income and expenses, last ${points.length} months`} size="sm" />
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={summary} data-testid="trend-chart" className="w-full">
        <line x1={SIDE_PAD} x2={WIDTH - SIDE_PAD} y1={BASELINE} y2={BASELINE} className="stroke-border" strokeWidth="1" />
        {points.map((p, i) => {
          const cx = SIDE_PAD + groupWidth * (i + 0.5);
          const income = barHeight(p.income, ceiling);
          const expense = barHeight(p.expense, ceiling);
          const selected = p.month === selectedMonth;
          const partialProps = (tone: 'success' | 'danger') =>
            p.complete
              ? { className: tone === 'success' ? 'fill-success' : 'fill-danger' }
              : { className: tone === 'success' ? 'fill-none stroke-success' : 'fill-none stroke-danger', strokeWidth: 1.5, strokeDasharray: '4 3' };
          return (
            <g key={p.month} data-testid={`trend-month-${p.month}`}>
              <rect x={cx - BAR_WIDTH - 2} y={BASELINE - income} width={BAR_WIDTH} height={income} rx="2" {...partialProps('success')}>
                <title>{`${shortMonth(p.month)} income ${fmt(p.income)}`}</title>
              </rect>
              <rect x={cx + 2} y={BASELINE - expense} width={BAR_WIDTH} height={expense} rx="2" {...partialProps('danger')}>
                <title>{`${shortMonth(p.month)} expenses ${fmt(p.expense)}`}</title>
              </rect>
              <text
                x={cx} y={BASELINE + 18} textAnchor="middle" fontSize="12"
                className={selected ? 'fill-ink font-semibold' : 'fill-muted'}
              >
                {shortMonth(p.month)}{p.complete ? '' : '*'}
              </text>
            </g>
          );
        })}
        {average !== null && (
          <g data-testid="trend-average">
            <line
              x1={SIDE_PAD} x2={WIDTH - SIDE_PAD}
              y1={BASELINE - (average / ceiling) * PLOT_HEIGHT} y2={BASELINE - (average / ceiling) * PLOT_HEIGHT}
              className="stroke-danger" strokeWidth="1" strokeDasharray="5 4" strokeOpacity="0.7"
            />
          </g>
        )}
      </svg>
      <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
        <span><span className="text-success" aria-hidden="true">●</span> Income</span>
        <span><span className="text-danger" aria-hidden="true">●</span> Expenses</span>
        {average !== null && <span data-testid="trend-average-note">Dashed line: average spend, {fmt(average)}, over the months that are whole</span>}
        {anyPartial && <span data-testid="trend-partial-note">* Only part of the month is in, so its bars run lower than the real totals</span>}
      </div>
    </FinoraCard>
  );
}
