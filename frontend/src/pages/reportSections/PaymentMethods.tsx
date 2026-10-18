import { FinoraCard, SectionHeader } from '../../design-system';
import { formatShare, type MethodBreakdown } from './methodBreakdown';
import { fmt } from './reportFormat';

/**
 * How the month's spending was paid, ranked, with each method's share of the whole. The methods are
 * read from the account type and the wording on the statements, so some spending names none: it is
 * shown as its own "Not identified" row with its share rather than spread over the others, and a
 * line under the card says where the methods come from. The card is not drawn at all when too little
 * of the spending has a named method (see methodBreakdown).
 */
export function PaymentMethods({ breakdown }: { breakdown: MethodBreakdown | null }) {
  if (!breakdown) return null;
  const largest = breakdown.named[0].amount;
  return (
    <FinoraCard>
      <SectionHeader title="How you paid" size="sm" />
      <ul className="space-y-3" data-testid="payment-methods">
        {breakdown.named.map((r) => (
          <li key={r.method}>
            <div className="flex items-baseline justify-between gap-3 text-sm">
              <span className="truncate text-ink">{r.label}</span>
              <span className="flex-shrink-0 tabular-nums text-ink">
                {fmt(r.amount)} <span className="text-xs text-muted">{formatShare(r.share)}</span>
              </span>
            </div>
            <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-border" aria-hidden="true">
              <div className="h-full rounded-full bg-primary" style={{ width: `${(r.amount / largest) * 100}%` }} />
            </div>
          </li>
        ))}
        {breakdown.unidentified && (
          <li data-testid="payment-methods-unidentified" className="border-t border-border pt-3">
            <div className="flex items-baseline justify-between gap-3 text-sm text-muted">
              <span>{breakdown.unidentified.label}</span>
              <span className="flex-shrink-0 tabular-nums">
                {fmt(breakdown.unidentified.amount)} <span className="text-xs">{formatShare(breakdown.unidentified.share)}</span>
              </span>
            </div>
          </li>
        )}
      </ul>
      <p className="mt-3 text-xs text-muted">
        Worked out from the account and the wording on your statements. Not every bank prints how a payment was made.
      </p>
    </FinoraCard>
  );
}
