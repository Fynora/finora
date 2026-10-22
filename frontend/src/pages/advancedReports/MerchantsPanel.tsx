import { Link } from 'react-router-dom';
import { Store } from 'lucide-react';
import type { AdvancedReport } from '../../api/endpoints';
import { EmptyState, FinoraCard, SectionHeader } from '../../design-system';
import { MerchantLogo } from '../../components/MerchantLogo';
import { fmt } from '../reportSections/reportFormat';
import { ledgerHref, monthBounds } from './advancedFormat';
import { ChangeLine } from './ChangeLine';
import { Sparkline } from './Sparkline';

const share = (pct: number) => (pct < 1 ? '<1%' : `${Math.round(pct)}%`);

/**
 * The merchants the period's spending went to, ranked. Only spending that names a merchant can be
 * ranked, so the card says how much of the spending that is rather than letting the ranking pass
 * for the whole. "Open the transactions" is the Ledger's own search for the merchant's name over the
 * period: a search, not a replay of the figure, so its rows can differ from the amount shown.
 */
export function MerchantsPanel({ report, limit, onViewAll }: { report: AdvancedReport; limit?: number; onViewAll?: () => void }) {
  const { merchants, period } = report;
  const rows = limit ? merchants.rows.slice(0, limit) : merchants.rows;
  const largest = Math.max(...merchants.rows.map((m) => m.amount), 1);
  const span = period.startMonth && period.endMonth
    ? { from: monthBounds(period.startMonth).from, to: monthBounds(period.endMonth).to }
    : null;

  return (
    <FinoraCard>
      <SectionHeader title="Top merchants" />
      {rows.length === 0 ? (
        <EmptyState icon={Store} iconBg="bg-surface" iconColor="text-ink" title="No merchant spending yet" desc="Import a statement or add transactions to see where you spend the most." />
      ) : (
        <>
          <ul className="space-y-3" data-testid="merchant-rows">
            {rows.map((m) => (
              <li key={m.merchantId} className="flex items-start gap-3" data-testid={`merchant-${m.name}`}>
                <MerchantLogo merchant={m.name} size={28} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-3 text-sm">
                    {span ? (
                      <Link to={ledgerHref({ ...span, categoryName: null, keyword: m.name })} className="min-w-0 truncate text-ink hover:underline">
                        {m.name}
                      </Link>
                    ) : <span className="min-w-0 truncate text-ink">{m.name}</span>}
                    <span className="flex-shrink-0 tabular-nums text-ink">
                      {fmt(m.amount)} <span className="text-xs text-muted">{share(m.sharePct)}</span>
                    </span>
                  </div>
                  <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-border" aria-hidden="true">
                    <div className="h-full rounded-full bg-primary" style={{ width: `${(m.amount / largest) * 100}%` }} />
                  </div>
                  <div className="mt-1 flex items-center justify-between gap-3">
                    <span className="text-2xs text-muted">{m.transactionCount} {m.transactionCount === 1 ? 'purchase' : 'purchases'}</span>
                    <span className="flex items-center gap-2">
                      <ChangeLine previousAmount={m.previousAmount} changeAmount={m.changeAmount} changePct={m.changePct} />
                      {!limit && <Sparkline values={m.monthly} />}
                    </span>
                  </div>
                </div>
              </li>
            ))}
          </ul>
          <p className="mt-4 text-xs text-muted" data-testid="merchant-coverage">
            {Math.round(merchants.attributedSharePct)}% of your spending is tied to a named merchant, so this ranks that part. The five largest take {Math.round(merchants.topFiveSharePct)}% of all your spending.
          </p>
        </>
      )}
      {limit && onViewAll && merchants.rows.length > limit && (
        <button type="button" onClick={onViewAll} className="mt-3 text-xs font-medium text-primary underline underline-offset-2">
          View all merchants
        </button>
      )}
    </FinoraCard>
  );
}
