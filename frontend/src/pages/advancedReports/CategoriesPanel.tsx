import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { AdvancedCategory, AdvancedReport } from '../../api/endpoints';
import { EmptyState, FinoraCard, SectionHeader } from '../../design-system';
import { PieChart } from 'lucide-react';
import { fmt } from '../reportSections/reportFormat';
import { ledgerHref, monthBounds } from './advancedFormat';
import { ChangeLine } from './ChangeLine';
import { Sparkline } from './Sparkline';

/** "1%" / "<1%": a category with real money in it must not read "0%". */
const share = (pct: number) => (pct < 1 ? '<1%' : `${Math.round(pct)}%`);

/**
 * Where the period's spending went, by category. Three different things are shown side by side and
 * never merged: how much (the amount and the bar), how big a part of the spending (the share), and
 * how it moved (the change against the earlier period, and the shape of the months). Sorting by
 * change finds fast-growing categories that the amount ranking would bury.
 */
export function CategoriesPanel({ report, limit, onViewAll }: { report: AdvancedReport; limit?: number; onViewAll?: () => void }) {
  const { categories, comparison, period } = report;
  const [sort, setSort] = useState<'amount' | 'change'>('amount');
  const canSortByChange = comparison.reliable;
  const rows: AdvancedCategory[] = sort === 'change' && canSortByChange
    ? [...categories.rows].sort((a, b) => Math.abs(b.changeAmount ?? 0) - Math.abs(a.changeAmount ?? 0))
    : categories.rows;
  const shown = limit ? rows.slice(0, limit) : rows;
  const largest = Math.max(...categories.rows.map((c) => c.amount), 1);
  const span = period.startMonth && period.endMonth
    ? { from: monthBounds(period.startMonth).from, to: monthBounds(period.endMonth).to }
    : null;

  return (
    <FinoraCard>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <SectionHeader title="Top spending categories" />
        {canSortByChange && !limit && (
          <div role="group" aria-label="Sort categories" className="flex rounded-lg border border-border p-0.5 text-xs">
            {(['amount', 'change'] as const).map((s) => (
              <button
                key={s} type="button" onClick={() => setSort(s)} aria-pressed={sort === s}
                className={`rounded-md px-2.5 py-1 font-medium ${sort === s ? 'bg-primary text-on-primary' : 'text-muted hover:text-ink'}`}
              >
                {s === 'amount' ? 'By amount' : 'By change'}
              </button>
            ))}
          </div>
        )}
      </div>
      {shown.length === 0 ? (
        <EmptyState icon={PieChart} iconBg="bg-surface" iconColor="text-ink" title="No spending in this period" desc="Your highest spending categories appear here once there is spending to show." />
      ) : (
        <ul className="space-y-3" data-testid="category-rows">
          {shown.map((c) => (
            <li key={c.name} data-testid={`category-${c.name}`}>
              <div className="flex items-baseline justify-between gap-3 text-sm">
                {span ? (
                  <Link
                    to={ledgerHref({ ...span, categoryName: c.name === 'Uncategorized' ? null : c.name, keyword: null })}
                    className="min-w-0 truncate text-ink hover:underline"
                  >
                    {c.name}
                  </Link>
                ) : <span className="min-w-0 truncate text-ink">{c.name}</span>}
                <span className="flex-shrink-0 tabular-nums text-ink">
                  {fmt(c.amount)} <span className="text-xs text-muted">{share(c.sharePct)}</span>
                </span>
              </div>
              <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-border" aria-hidden="true">
                <div className="h-full rounded-full bg-primary" style={{ width: `${(c.amount / largest) * 100}%` }} />
              </div>
              <div className="mt-1 flex items-center justify-between gap-3">
                <span className="text-2xs text-muted">{c.transactionCount} {c.transactionCount === 1 ? 'purchase' : 'purchases'}</span>
                <span className="flex items-center gap-2">
                  <ChangeLine previousAmount={c.previousAmount} changeAmount={c.changeAmount} changePct={c.changePct} />
                  <Sparkline values={c.monthly} />
                </span>
              </div>
            </li>
          ))}
          {!limit && categories.otherAmount > 0 && (
            <li className="border-t border-border pt-3" data-testid="category-other">
              <div className="flex items-baseline justify-between gap-3 text-sm text-muted">
                <span>Other ({categories.categoryCount - categories.rows.length} more)</span>
                <span className="tabular-nums">{fmt(categories.otherAmount)} <span className="text-xs">{share(categories.otherSharePct)}</span></span>
              </div>
            </li>
          )}
        </ul>
      )}
      {limit && onViewAll && categories.rows.length > limit && (
        <button type="button" onClick={onViewAll} className="mt-3 text-xs font-medium text-primary underline underline-offset-2">
          View all categories
        </button>
      )}
    </FinoraCard>
  );
}
