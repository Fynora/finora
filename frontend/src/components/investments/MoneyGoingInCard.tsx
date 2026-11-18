import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { categoriesApi, transactionsApi } from '../../api/endpoints';
import { FinoraCard, Skeleton } from '../../design-system';
import { useAsyncGuard } from '../../hooks/useAsyncGuard';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { moneyInPeriod, summarizeMoneyIn, type MoneyInSummary } from '../../lib/investmentSummary';
import type { Transaction } from '../../types';

const INVESTMENTS_CATEGORY = 'Investments';
// The backend clamps a page to 100 rows, so a year is read page by page. The cap keeps a
// pathological account from becoming an unbounded loop; hitting it is said on screen ("At least").
const PAGE_SIZE = 100;
const MAX_PAGES = 10;

function fmt(n: number) {
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

/** Everything filed under Investments in the period: outflows only, the SIPs and broker transfers. */
async function loadPayments(dateFrom: string, dateTo: string): Promise<{ rows: Transaction[]; truncated: boolean }> {
  const categories = await categoriesApi.list();
  const category = categories.find((c) => c.name.trim().toLowerCase() === INVESTMENTS_CATEGORY.toLowerCase());
  if (!category) return { rows: [], truncated: false };
  const rows: Transaction[] = [];
  let truncated = false;
  for (let page = 0; page < MAX_PAGES; page++) {
    const result = await transactionsApi.search({
      categoryId: category.id, type: 'EXPENSE', dateFrom, dateTo, page, size: PAGE_SIZE, sortField: 'date', sortDir: 'desc',
    });
    rows.push(...result.content);
    if (page + 1 >= result.totalPages) break;
    if (page + 1 >= MAX_PAGES) truncated = true;
  }
  return { rows, truncated };
}

/**
 * Money put into investments over 12 months, found in imported statements. Deliberately separate
 * from the holdings: a holding is a value someone typed, these are bank transactions, and the two
 * are never matched, so this says nothing about what the holdings are worth.
 */
export function MoneyGoingInCard({ today }: { today: Date }) {
  const { dateFrom, dateTo } = moneyInPeriod(today);
  const [summary, setSummary] = useState<(MoneyInSummary & { truncated: boolean }) | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const { beginRequest } = useAsyncGuard();
  const showSkeleton = useDelayedLoading(loading);

  useEffect(() => {
    const isCurrent = beginRequest();
    setLoading(true);
    setFailed(false);
    loadPayments(dateFrom, dateTo)
      .then(({ rows, truncated }) => {
        if (isCurrent()) setSummary({ ...summarizeMoneyIn(rows, new Date(`${dateTo}T12:00:00`)), truncated });
      })
      .catch(() => { if (isCurrent()) { setSummary(null); setFailed(true); } })
      .finally(() => { if (isCurrent()) setLoading(false); });
  }, [beginRequest, dateFrom, dateTo]);

  const total = summary ? `${summary.truncated ? 'At least ' : ''}${fmt(summary.total)}` : '';
  const average = summary ? fmt(summary.monthlyAverage) : '';
  return (
    <FinoraCard padding="lg">
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-display text-base font-extrabold text-ink">Money going in</h2>
        <p className="text-[13px] text-muted">Last 12 months</p>
      </div>
      {loading ? (
        <Skeleton.Region label="Loading money going in">
          {showSkeleton && <Skeleton.Block className="mt-4 h-24" />}
        </Skeleton.Region>
      ) : failed || !summary ? (
        <p className="mt-3 text-sm text-muted">Could not load your investment payments.</p>
      ) : summary.count === 0 ? (
        <p className="mt-3 text-sm text-muted">No SIPs or broker payments found in your statements yet.</p>
      ) : (
        <>
          <p className="mt-2 font-display text-2xl font-extrabold tracking-tight text-ink">{total}</p>
          <p className="text-[13px] text-muted">{`About ${average} a month · ${summary.count} ${summary.count === 1 ? 'payment' : 'payments'}`}</p>
          <div role="img" aria-label={`Last 12 months: ${total}, about ${average} a month`} className="mt-3 flex gap-1.5">
            {summary.months.map((m) => (
              <div key={m.key} className="flex min-w-0 flex-1 flex-col items-center gap-1">
                <div className="flex h-16 w-full items-end">
                  <div className="w-full rounded-t bg-success" style={{ height: `${m.heightFrac * 100}%` }} />
                </div>
                <span aria-hidden="true" className="text-[11px] text-muted">{m.letter}</span>
              </div>
            ))}
          </div>
          <Link
            to={`/app/transactions?category=${encodeURIComponent(INVESTMENTS_CATEGORY)}&from=${dateFrom}&to=${dateTo}`}
            className="mt-3 inline-block text-[13px] font-semibold text-primary underline-offset-4 hover:underline"
          >
            See payments
          </Link>
        </>
      )}
    </FinoraCard>
  );
}
