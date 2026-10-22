import { Globe } from 'lucide-react';
import type { InternationalSpend } from '../../api/endpoints';
import { EmptyState, FinoraCard, SectionHeader } from '../../design-system';
import { formatForeignAmount } from '../../lib/foreignAmount';
import { fmt } from '../reportSections/reportFormat';

/**
 * Spend the statements themselves marked international (an "International Transactions" table),
 * split into foreign-currency purchases and everything else (GST, FX markup, anything billed in
 * rupees -- never labelled "fees", since a rupee-billed purchase lands there too), with purchases
 * grouped by the currency they were made in. Rupee figures are what was billed. Only what the
 * statements list is counted: nothing is inferred from a merchant's name.
 */
export function InternationalCard({ data }: { data: InternationalSpend }) {
  const max = Math.max(...data.byCurrency.map((c) => c.rupeeTotal), 1);
  return (
    <FinoraCard>
      <SectionHeader title="International spend" />
      {data.transactionCount === 0 ? (
        <EmptyState
          icon={Globe} iconBg="bg-surface" iconColor="text-ink" title="No international spend"
          desc="Card transactions your statement lists under International Transactions will appear here."
        />
      ) : (
        <div className="space-y-5">
          <dl className="grid grid-cols-3 gap-3 text-sm">
            <div>
              <dt className="text-2xs uppercase text-muted">Total</dt>
              <dd className="font-semibold text-ink">{fmt(data.totalSpend)}</dd>
              <dd className="text-2xs text-muted">{data.transactionCount} txns</dd>
            </div>
            <div>
              <dt className="text-2xs uppercase text-muted">Foreign purchases</dt>
              <dd className="font-semibold text-ink">{fmt(data.purchasesSpend)}</dd>
            </div>
            <div>
              <dt className="text-2xs uppercase text-muted">Other charges</dt>
              <dd className="font-semibold text-ink">{fmt(data.otherChargesSpend)}</dd>
            </div>
          </dl>
          <p className="text-2xs text-muted">
            Other charges are international rows with no foreign amount printed: GST, FX markup, and anything billed in rupees.
          </p>
          {data.byCurrency.length > 0 && (
            <ul className="space-y-3">
              {data.byCurrency.map((c) => (
                <li key={c.currency}>
                  <div className="flex items-baseline justify-between gap-3 text-sm">
                    <span className="text-ink">{c.currency}</span>
                    <span className="tabular-nums text-ink">{fmt(c.rupeeTotal)}</span>
                  </div>
                  <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-border" aria-hidden="true">
                    <div className="h-full rounded-full bg-primary" style={{ width: `${(c.rupeeTotal / max) * 100}%` }} />
                  </div>
                  <p className="mt-0.5 text-2xs text-muted">{formatForeignAmount(c.currency, c.foreignTotal)} · {c.transactionCount} txns</p>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </FinoraCard>
  );
}
