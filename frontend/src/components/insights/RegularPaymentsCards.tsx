import { CalendarDays, Check, X } from 'lucide-react';
import type { RecurringItem } from '../../api/endpoints';
import { FinoraCard, SectionHeader, Skeleton } from '../../design-system';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { dayMonth, rupees } from '../../lib/people';

/** "October": the month a date falls in, in full. */
function monthNameOf(date: string): string {
  const [y, m] = date.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long' });
}

const LOAD_FAILED = "Couldn't load your repeating payments.";

interface ComingUpProps {
  rows: { item: RecurringItem; past: boolean }[];
  stillToGoOut: number;
  /** The newest statement period end across the user's accounts (YYYY-MM-DD), or null. */
  latestStatementEnd: string | null;
  status: 'loading' | 'error' | 'ready';
}

/**
 * "Coming up" (website Insights spec): repeating payments by their next expected date, with what is
 * still to go out this month as the figure that matters. The data stops at the latest statement, so a
 * date already past may have been paid: it reads "expected around", is greyed, is left out of the
 * total, and the note says which statement would settle it. Shares expected in from people are in
 * "With people" (WithPeopleCard), not repeated here.
 */
export function ComingUpCard({ rows, stillToGoOut, latestStatementEnd, status }: ComingUpProps) {
  if (status === 'loading') return null;
  if (status === 'error') {
    return (
      <FinoraCard>
        <SectionHeader title="Coming up" />
        <p className="text-sm text-muted">{LOAD_FAILED}</p>
      </FinoraCard>
    );
  }
  if (rows.length === 0) return null;
  const firstPast = rows.find((r) => r.past);
  return (
    <FinoraCard>
      <SectionHeader title="Coming up" />
      <div className="mb-2">
        <p className="text-xs text-muted">Still to go out this month</p>
        <p className="text-xl font-semibold text-danger">{rupees(stillToGoOut)}</p>
      </div>
      <ul>
        {rows.map(({ item, past }) => {
          const when = past ? `expected around ${dayMonth(item.nextEstimate)}` : `around ${dayMonth(item.nextEstimate)}`;
          return (
            <li
              key={item.merchant}
              aria-label={`${item.merchant}, ${rupees(item.averageAmount)}, ${when}${past ? ', may already be paid' : ''}`}
              className="flex items-center gap-3 py-2 border-b border-dashed last:border-b-0 text-sm"
            >
              <CalendarDays size={16} aria-hidden className={past ? 'text-muted' : 'text-primary'} />
              <span className="flex-1 min-w-0">
                <span className={`block capitalize truncate ${past ? 'text-muted' : 'text-ink'}`}>{item.merchant}</span>
                <span className="block text-xs text-muted">{when}</span>
              </span>
              <span className={past ? 'text-muted' : 'text-danger'}>{rupees(item.averageAmount)}</span>
            </li>
          );
        })}
      </ul>
      {firstPast && (
        <p className="text-xs text-muted mt-2">
          {latestStatementEnd
            ? `Your latest statement ends ${dayMonth(latestStatementEnd)}. Upload ${monthNameOf(firstPast.item.nextEstimate)}'s to see if these were paid.`
            : 'Upload your latest statements to see if these were paid.'}
        </p>
      )}
    </FinoraCard>
  );
}

interface PaidRegularlyProps {
  rows: { item: RecurringItem; monthly: number; rising: boolean }[];
  monthlyTotal: number;
  yearlyTotal: number;
  status: 'loading' | 'error' | 'ready';
  /** Payees confirmed on this visit ("Yes, keep tracking this"). */
  confirmed: Set<string>;
  onConfirm: (merchant: string) => void;
  onDismiss: (merchant: string) => void;
}

/**
 * "Paid regularly" (website Insights spec): every repeating payment the server detected -- rent, EMIs
 * and SIPs as well as subscriptions, hence not "Subscriptions" -- led by the yearly total. A bill other
 * people share counts at the user's share.
 */
export function PaidRegularlyCard({ rows, monthlyTotal, yearlyTotal, status, confirmed, onConfirm, onDismiss }: PaidRegularlyProps) {
  const showSkeleton = useDelayedLoading(status === 'loading');
  return (
    <FinoraCard>
      <SectionHeader title="Paid regularly" />
      {status === 'loading' ? (
        // The one loading region for the repeating payments: "Coming up" waits on the same request.
        <Skeleton.Region label="Loading repeating payments">
          {showSkeleton && (
            <div className="space-y-2">
              {[0, 1, 2].map((i) => <Skeleton.Block key={i} className="h-8 w-full" />)}
            </div>
          )}
        </Skeleton.Region>
      ) : status === 'error' ? (
        <p className="text-sm text-muted">{LOAD_FAILED}</p>
      ) : rows.length === 0 ? (
        <div className="text-sm">
          <p className="text-ink font-medium">No repeating payments found yet</p>
          <p className="text-muted text-xs">This needs at least 2 payments to the same payee at a regular interval.</p>
        </div>
      ) : (
        <>
          <div className="mb-2">
            <p className="text-2xl font-semibold text-ink">
              {rupees(yearlyTotal)} <span className="text-sm font-normal text-muted">a year</span>
            </p>
            <p className="text-xs text-muted">{`${rupees(monthlyTotal)} a month across ${rows.length} payment${rows.length === 1 ? '' : 's'}`}</p>
          </div>
          <ul>
            {rows.map(({ item, rising }) => {
              const shared = item.yourShare != null && item.sharedWith != null;
              const shown = shared ? (item.yourShare as number) : item.averageAmount;
              const spoken = shared
                ? `your share ${rupees(shown)} of ${rupees(item.averageAmount)}, split with ${item.sharedWith}`
                : rupees(item.averageAmount);
              const isConfirmed = confirmed.has(item.merchant);
              return (
                <li
                  key={item.merchant}
                  aria-label={`${item.merchant}, ${item.label}, ${spoken}${rising ? ', latest payment went up' : ''}`}
                  className="flex items-center gap-2 py-2 border-b border-dashed last:border-b-0 text-sm"
                >
                  <span className="flex-1 min-w-0">
                    <span className="block capitalize truncate text-ink">{item.merchant}</span>
                    <span className="block text-xs text-muted">
                      {shared
                        ? `${item.label} · your share of ${rupees(item.averageAmount)}, split with ${item.sharedWith}`
                        : `${item.label} · seen ${item.occurrences}×`}
                    </span>
                  </span>
                  <span className="text-danger">
                    {rupees(shown)}
                    {rising && <span aria-hidden> ▲</span>}
                  </span>
                  <button
                    type="button"
                    onClick={() => onConfirm(item.merchant)}
                    disabled={isConfirmed}
                    aria-label={isConfirmed ? `${item.merchant} confirmed as recurring` : `Confirm ${item.merchant} as recurring`}
                    title={isConfirmed ? 'Confirmed' : 'Yes, keep tracking this'}
                    className="text-muted hover:text-success disabled:hover:text-muted disabled:opacity-50 p-1"
                  >
                    <Check size={14} className={isConfirmed ? 'text-success' : undefined} />
                  </button>
                  <button
                    type="button"
                    onClick={() => onDismiss(item.merchant)}
                    aria-label={`Not recurring: dismiss ${item.merchant}`}
                    title="Not recurring"
                    className="text-muted hover:text-ink p-1"
                  >
                    <X size={14} />
                  </button>
                </li>
              );
            })}
          </ul>
        </>
      )}
    </FinoraCard>
  );
}
