import { Link } from 'react-router-dom';
import type { BiggestSpend } from '../../api/endpoints';
import { FinoraCard, SectionHeader } from '../../design-system';
import { fmt, shortDate } from './reportFormat';

/** The Ledger narrowed to one purchase: its name on its day. The Ledger reads `q`, `from` and `to`. */
const ledgerLinkFor = (s: BiggestSpend) =>
  `/app/transactions?${new URLSearchParams({ q: s.label, from: s.date, to: s.date }).toString()}`;

/**
 * The month's largest purchases, from the server: the same rows and refund netting as the category
 * list, so each one is something the report counted as spend. Drawn only when there is one.
 */
export function BiggestSpends({ items }: { items: BiggestSpend[] | undefined }) {
  if (!items || items.length === 0) return null;
  return (
    <FinoraCard>
      <SectionHeader title="Biggest spends" size="sm" />
      <ul className="divide-y divide-border" data-testid="biggest-spends">
        {items.map((s) => (
          <li key={s.id}>
            <Link
              to={ledgerLinkFor(s)}
              aria-label={`${s.label}, ${fmt(s.amount)} on ${shortDate(s.date)}, ${s.category}. Open it in the ledger.`}
              className="flex items-center justify-between gap-3 rounded-md py-2 hover:bg-primary-light/40"
            >
              <span className="min-w-0">
                <span className="block truncate text-sm text-ink">{s.label}</span>
                <span className="block truncate text-xs text-muted">{shortDate(s.date)} · {s.category}</span>
              </span>
              <span className="flex-shrink-0 text-sm tabular-nums text-ink">{fmt(s.amount)}</span>
            </Link>
          </li>
        ))}
      </ul>
    </FinoraCard>
  );
}
