import { useCallback, useEffect, useState } from 'react';
import { Repeat } from 'lucide-react';
import { categoriesApi, transactionsApi } from '../api/endpoints';
import type { Transaction } from '../types';
import { formatDate } from '../utils/date';
import { useAsyncGuard } from '../hooks/useAsyncGuard';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { FinoraCard, EmptyState, Skeleton } from '../design-system';

const INVESTMENTS_CATEGORY = 'Investments';
// The backend clamps a page to 100 rows (PageBounds.DEFAULT_MAX_SIZE), so a longer period is
// fetched page by page. The cap keeps a pathological account from turning this section into an
// unbounded loop; hitting it is reported to the user rather than hidden -- see `truncated`.
const PAGE_SIZE = 100;
const MAX_PAGES = 10;
// How many of the newest rows are listed. The total above them covers the whole period.
const LISTED_ROWS = 8;
// A row the reconciliation engine has already judged to be a second copy of another, or that a
// re-uploaded statement replaced, is not a second investment. Every other status stays counted:
// INVESTMENT_TRANSFER is the normal one for these rows, and TRANSFER means the user tracks the
// receiving holding too, which is still money invested.
const NOT_REAL_ACTIVITY = new Set(['DUPLICATE', 'SUPERSEDED']);

const PERIODS = [
  { months: 3, label: 'Last 3 months' },
  { months: 6, label: 'Last 6 months' },
  { months: 12, label: 'Last 12 months' },
] as const;

function fmt(n: number) {
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

/** A local calendar date as YYYY-MM-DD -- what the transactions API's dateFrom/dateTo expect. */
function isoDate(d: Date) {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function periodStart(months: number) {
  const d = new Date();
  d.setMonth(d.getMonth() - months);
  return isoDate(d);
}

interface Activity {
  rows: Transaction[];
  total: number;
  count: number;
  truncated: boolean;
}

async function loadActivity(months: number): Promise<Activity> {
  const categories = await categoriesApi.list();
  // Every user is seeded with the system "Investments" category; one that somehow lacks it has, by
  // definition, nothing filed under it.
  const category = categories.find((c) => c.name.trim().toLowerCase() === INVESTMENTS_CATEGORY.toLowerCase());
  if (!category) return { rows: [], total: 0, count: 0, truncated: false };

  const collected: Transaction[] = [];
  let truncated = false;
  for (let page = 0; page < MAX_PAGES; page++) {
    const result = await transactionsApi.search({
      categoryId: category.id,
      // Outflows only: these are the SIPs and broker transfers. A redemption or dividend is money
      // coming back, not money invested, and would only make the total mean something else.
      type: 'EXPENSE',
      dateFrom: periodStart(months),
      dateTo: isoDate(new Date()),
      page,
      size: PAGE_SIZE,
      sortField: 'date',
      sortDir: 'desc',
    });
    collected.push(...result.content);
    if (page + 1 >= result.totalPages) break;
    if (page + 1 >= MAX_PAGES) truncated = true;
  }

  const rows = collected.filter((t) => !NOT_REAL_ACTIVITY.has(t.reconciliationStatus));
  return { rows, total: rows.reduce((sum, t) => sum + t.amount, 0), count: rows.length, truncated };
}

/**
 * The SIPs and broker transfers found in the user's statements: everything filed under the
 * Investments category, newest first, with what was invested over the chosen period.
 *
 * This is deliberately separate from the holdings list above it. A holding is a balance the user
 * types in; these are transactions that arrived by importing a statement, and the two are never
 * reconciled against each other -- so this section makes no claim about what the holdings are worth.
 */
export function InvestmentActivity() {
  const [months, setMonths] = useState<number>(12);
  const [activity, setActivity] = useState<Activity | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const { beginRequest } = useAsyncGuard();
  const showSkeleton = useDelayedLoading(loading);

  const load = useCallback((forMonths: number) => {
    // A period switch while an earlier request is still running must not let the older, slower
    // response land last and show numbers for the wrong period.
    const isCurrent = beginRequest();
    setLoading(true);
    setFailed(false);
    return loadActivity(forMonths)
      .then((result) => { if (isCurrent()) setActivity(result); })
      .catch(() => { if (isCurrent()) { setActivity(null); setFailed(true); } })
      .finally(() => { if (isCurrent()) setLoading(false); });
  }, [beginRequest]);

  useEffect(() => { void load(months); }, [load, months]);

  const periodLabel = PERIODS.find((p) => p.months === months)?.label.toLowerCase() ?? '';

  return (
    <FinoraCard>
      <div className="flex justify-between items-center gap-3 mb-1">
        <h2 className="font-semibold text-ink">SIPs &amp; broker transfers</h2>
        <select
          aria-label="Period"
          value={months}
          onChange={(e) => setMonths(Number(e.target.value))}
          className="bg-card text-ink border rounded px-2 py-1 text-xs"
        >
          {PERIODS.map((p) => <option key={p.months} value={p.months}>{p.label}</option>)}
        </select>
      </div>
      <p className="text-xs text-muted mb-4">
        Payments to brokers and mutual funds found in the statements you import, filed under Investments.
      </p>

      {loading ? (
        <Skeleton.Region label="Loading your investment activity">
          {showSkeleton && (
            <div className="space-y-2">
              {Array.from({ length: 3 }, (_, i) => (
                <div key={i} className="flex justify-between items-center border-b border-dashed py-2">
                  <Skeleton.Text width="w-2/5" />
                  <Skeleton.Text width="w-20" className="h-2.5" />
                </div>
              ))}
            </div>
          )}
        </Skeleton.Region>
      ) : failed || !activity ? (
        // Not the empty state: "no SIPs yet" would be a false claim to someone whose fetch failed.
        <p className="text-muted text-sm">Couldn't load your investment activity — please try again later.</p>
      ) : activity.count === 0 ? (
        <EmptyState
          icon={Repeat}
          iconBg="bg-accent-blue-bg"
          iconColor="text-accent-blue"
          title="No SIPs or broker transfers yet"
          desc={`Nothing filed under Investments in the ${periodLabel}. Import a statement that has them and they will appear here.`}
        />
      ) : (
        <>
          <div className="mb-3">
            <div className="text-2xs uppercase text-muted">Invested in the {periodLabel}</div>
            <div className="text-xl font-semibold text-ink" data-testid="invested-total">
              {activity.truncated ? 'At least ' : ''}{fmt(activity.total)}
            </div>
            <div className="text-2xs text-muted">
              {activity.count} {activity.count === 1 ? 'payment' : 'payments'}
              {activity.truncated ? ` — counting the most recent ${MAX_PAGES * PAGE_SIZE} only` : ''}
            </div>
          </div>
          <div className="space-y-2">
            {activity.rows.slice(0, LISTED_ROWS).map((t) => (
              <div key={t.id} className="flex justify-between items-center gap-3 border-b border-dashed py-2 text-sm">
                <span className="min-w-0">
                  <span className="block truncate">{t.description}</span>
                  <span className="block text-2xs text-muted">{formatDate(t.date)}</span>
                </span>
                <span className="flex-shrink-0">{fmt(t.amount)}</span>
              </div>
            ))}
          </div>
          {activity.count > LISTED_ROWS && (
            <p className="text-2xs text-muted mt-2">
              Showing the latest {LISTED_ROWS} of {activity.count}. The full list is in your Ledger.
            </p>
          )}
        </>
      )}
    </FinoraCard>
  );
}
