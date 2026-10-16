import { useState } from 'react';
import { Link } from 'react-router-dom';
import { PieChart, Tag } from 'lucide-react';
import type { CategoryOption, InsightsData, ReportData } from '../../api/endpoints';
import { EmptyState, FinoraCard, SectionHeader } from '../../design-system';
import { COLOR_HEX, ICON_COMPONENTS } from '../../lib/categoryIcons';
import { categoryIdByName, ledgerLinkForCategory } from '../../lib/ledgerUrlFilters';
import { categoryRows, changeVsAverage, donutSlices, type CategoryRow } from './categoryShare';
import { fmt, lastDayOf, monthLabelLong } from './reportFormat';

const VISIBLE_ROWS = 8;
const RING_RADIUS = 38;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;
/** A hairline between slices, in ring length, so neighbouring categories that share a colour stay apart. */
const SLICE_GAP = 1.5;

type Mover = NonNullable<InsightsData['movers']>[number];

const CHANGE_STYLE = {
  up: 'bg-danger-bg text-danger',
  down: 'bg-success-bg text-success',
  flat: 'border border-border text-muted',
} as const;

function DonutChart({ rows, total, categories }: { rows: CategoryRow[]; total: number; categories: Map<string, CategoryOption> }) {
  const slices = donutSlices(rows);
  const colorOf = (name: string | null) =>
    COLOR_HEX[(name ? categories.get(name.trim().toLowerCase())?.color : undefined) ?? 'gray'] ?? COLOR_HEX.gray;
  let offset = 0;
  const summary = slices
    .slice(0, 4)
    .map((s) => `${s.category ?? 'Other'} ${Math.round(s.share)}%`)
    .join(', ');
  return (
    <svg
      viewBox="0 0 104 104" role="img" data-testid="category-donut" className="h-32 w-32 flex-shrink-0"
      aria-label={`Spending by category, ${fmt(total)} in total. Largest: ${summary}.`}
    >
      <g transform="rotate(-90 52 52)" fill="none" strokeWidth="14">
        {slices.map((s) => {
          const length = (s.share / 100) * RING_CIRCUMFERENCE;
          // A ring of one slice has no neighbour to keep apart from, so it closes without a notch.
          const drawn = Math.max(length - (slices.length > 1 ? SLICE_GAP : 0), 0.01);
          const circle = (
            <circle
              key={s.category ?? '__other'} cx="52" cy="52" r={RING_RADIUS} stroke={colorOf(s.category)}
              strokeDasharray={`${drawn} ${RING_CIRCUMFERENCE - drawn}`} strokeDashoffset={-offset}
            />
          );
          offset += length;
          return circle;
        })}
      </g>
      <text x="52" y="50" textAnchor="middle" fontSize="12" fontWeight="700" className="fill-ink font-display">{fmt(total)}</text>
      <text x="52" y="63" textAnchor="middle" fontSize="8.5" className="fill-muted">total spend</text>
    </svg>
  );
}

function RowBody({ row, largest, category, mover }: { row: CategoryRow; largest: number; category: CategoryOption | undefined; mover: Mover | undefined }) {
  const Icon = ICON_COMPONENTS[category?.icon ?? 'tag'] ?? Tag;
  const color = COLOR_HEX[category?.color ?? 'gray'] ?? COLOR_HEX.gray;
  const change = changeVsAverage(mover?.pctChange);
  return (
    <>
      <span className="flex h-7 w-7 items-center justify-center rounded-lg" style={{ backgroundColor: `${color}1f` }}>
        <Icon size={14} style={{ color }} aria-hidden="true" />
      </span>
      <span className="min-w-0">
        <span className="flex items-center gap-2">
          <span className="truncate text-sm text-ink">{row.category}</span>
          {change && (
            <span data-testid="category-change" className={`flex-shrink-0 rounded-md px-1.5 py-0.5 text-2xs font-medium ${CHANGE_STYLE[change.tone]}`}>
              {change.text}
            </span>
          )}
        </span>
        <span className="mt-1 block h-1.5 overflow-hidden rounded-full bg-border" aria-hidden="true">
          <span className="block h-full rounded-full" style={{ width: `${(row.amount / largest) * 100}%`, backgroundColor: color }} />
        </span>
      </span>
      <span className="text-right text-sm tabular-nums text-ink">{fmt(row.amount)}</span>
      <span className="w-10 text-right text-xs tabular-nums text-muted">{Math.round(row.share)}%</span>
    </>
  );
}

// Fixed widths for the amount and share columns, so every row's figures line up under each other;
// an `auto` column sizes each row on its own and left them ragged. 5.5rem holds "₹12,34,567".
const ROW_GRID = 'grid grid-cols-[1.75rem_minmax(0,1fr)_5.5rem_2.5rem] items-center gap-x-3 py-2';

/**
 * Where the month's spending went: a donut and a ranked list that add up to the same total (the
 * rows' own sum, never the report's Expense). A row opens the Ledger for that category and month
 * only when a real category with that name exists -- the report's catch-all "Uncategorized" bucket
 * also holds transactions in deleted categories, which the Ledger cannot filter to, so it is not a
 * link rather than a link that shows the wrong rows. The change chip is the server's figure against
 * the user's recent average (insights movers); a category with no history gets none.
 */
export function CategoryBreakdown({ report, categories, movers }: {
  report: ReportData;
  categories: CategoryOption[];
  movers: InsightsData['movers'] | null;
}) {
  const [showAll, setShowAll] = useState(false);
  const { rows, total } = categoryRows(report.categories);
  const byName = new Map(categories.map((c) => [c.name.trim().toLowerCase(), c]));
  const moverByName = new Map((movers ?? []).map((m) => [m.category.trim().toLowerCase(), m]));
  const shown = showAll ? rows : rows.slice(0, VISIBLE_ROWS);
  const largest = rows[0]?.amount ?? 1;
  const hasChips = rows.some((r) => changeVsAverage(moverByName.get(r.category.trim().toLowerCase())?.pctChange));

  return (
    <FinoraCard>
      <SectionHeader title="Where your money went" />
      {rows.length === 0 ? (
        <EmptyState
          icon={PieChart}
          iconBg="bg-accent-purple-bg"
          iconColor="text-accent-purple"
          title="No expenses recorded"
          desc={`Nothing was spent in ${monthLabelLong(report.month)} in any category.`}
        />
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-5">
            <DonutChart rows={rows} total={total} categories={byName} />
            <p className="min-w-[10rem] flex-1 text-xs text-muted">
              {hasChips ? '▲ ▼ compare each category with your recent average. ' : ''}
              Shares are of the total spend shown here.
            </p>
          </div>
          <ul className="mt-3 divide-y divide-border" data-testid="category-rows">
            {shown.map((row) => {
              const key = row.category.trim().toLowerCase();
              const category = byName.get(key);
              const body = <RowBody row={row} largest={largest} category={category} mover={moverByName.get(key)} />;
              const linkable = categoryIdByName(categories, row.category) !== undefined;
              return (
                <li key={row.category}>
                  {linkable ? (
                    <Link
                      to={ledgerLinkForCategory(row.category, report.month, lastDayOf(report.month))}
                      className={`${ROW_GRID} rounded-md hover:bg-primary-light/40`}
                      aria-label={`${row.category}: ${fmt(row.amount)}, ${Math.round(row.share)}% of spending. Open its transactions.`}
                    >
                      {body}
                    </Link>
                  ) : (
                    <div className={ROW_GRID}>{body}</div>
                  )}
                </li>
              );
            })}
          </ul>
          {rows.length > VISIBLE_ROWS && (
            <button
              type="button"
              onClick={() => setShowAll((v) => !v)}
              aria-expanded={showAll}
              className="mt-2 text-xs font-medium text-primary underline underline-offset-2"
            >
              {showAll ? 'Show fewer' : `Show all ${rows.length} categories`}
            </button>
          )}
        </>
      )}
    </FinoraCard>
  );
}
