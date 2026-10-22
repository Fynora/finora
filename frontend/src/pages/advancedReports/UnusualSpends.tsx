import { Link } from 'react-router-dom';
import type { AdvancedUnusualSpend } from '../../api/endpoints';
import { FinoraCard, SectionHeader } from '../../design-system';
import { fmt, shortDate } from '../reportSections/reportFormat';
import { ledgerHref } from './advancedFormat';

/**
 * Purchases that were much larger than is usual for their category, by a rule written on the card:
 * at least three times the median of that category's other purchases. It says "larger than usual",
 * never "wrong": a big one-off is often exactly what the money was for. Left out when there are none.
 */
export function UnusualSpends({ items }: { items: AdvancedUnusualSpend[] }) {
  if (items.length === 0) return null;
  return (
    <FinoraCard>
      <SectionHeader title="Larger than usual" />
      <ul className="divide-y divide-border" data-testid="unusual-spends">
        {items.map((u) => (
          <li key={u.transactionId} className="py-2.5 first:pt-0 last:pb-0">
            <div className="flex items-baseline justify-between gap-3 text-sm">
              <span className="min-w-0 truncate text-ink">{u.label}</span>
              <span className="flex-shrink-0 font-medium tabular-nums text-ink">{fmt(u.amount)}</span>
            </div>
            <p className="mt-0.5 text-xs text-muted">
              {shortDate(u.date)} · {u.category} · about {Number(u.timesTypical.toFixed(1))} times the usual {fmt(u.typicalAmount)}
              {' · '}
              <Link
                to={ledgerHref({ from: u.date, to: u.date, categoryName: u.category, keyword: null })}
                className="font-medium text-primary underline underline-offset-2"
              >
                Open
              </Link>
            </p>
          </li>
        ))}
      </ul>
      <p className="mt-3 text-xs text-muted">
        A purchase shows here when it is at least three times the middle amount of your other purchases in the same category, and a meaningful part of the period's spending. It is a prompt to look, not a sign that anything is wrong.
      </p>
    </FinoraCard>
  );
}
