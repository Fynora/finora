import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Crown, Lock } from 'lucide-react';
import { analyticsApi } from '../../api/endpoints';
import { PremiumFeatureGate } from '../../components/PremiumFeatureGate';
import { FinoraCard, SectionHeader } from '../../design-system';
import { trackNavigation } from '../../lib/trackNavigation';
import { fmt } from './reportFormat';

const TOP = 5;

/** Real data, for a user who has Advanced Reports. Its own component so that the paid endpoint is
 *  only ever called once the entitlement is confirmed; a free user never reaches this. */
function MerchantsList({ month }: { month: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['reports', 'top-merchants', month],
    queryFn: () => analyticsApi.topMerchants(month),
    retry: false,
  });
  // A failure or an empty month leaves the card out: this is an extra, not the report.
  if (isLoading || !data || data.length === 0) return null;
  const rows = data.slice(0, TOP);
  const max = Math.max(...rows.map((m) => m.totalSpend), 1);
  return (
    <FinoraCard>
      <SectionHeader title="Top merchants" size="sm" />
      <ul className="space-y-2" data-testid="top-merchants">
        {rows.map((m) => (
          <li key={m.merchantId}>
            <div className="flex items-baseline justify-between gap-3 text-sm">
              <span className="truncate text-ink">{m.merchantName}</span>
              <span className="flex-shrink-0 tabular-nums text-ink">{fmt(m.totalSpend)}</span>
            </div>
            <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-border" aria-hidden="true">
              <div className="h-full rounded-full bg-primary" style={{ width: `${(m.totalSpend / max) * 100}%` }} />
            </div>
            <p className="mt-0.5 text-2xs text-muted">{m.transactionCount} {m.transactionCount === 1 ? 'transaction' : 'transactions'}</p>
          </li>
        ))}
      </ul>
      <p className="mt-3 text-xs text-muted">Counted by merchant, so it can differ from the categories above.</p>
      <Link to="/app/reports/advanced" className="mt-2 inline-block text-xs font-medium text-primary underline underline-offset-2">
        See more in Advanced Reports
      </Link>
    </FinoraCard>
  );
}

/** What a user without Advanced Reports sees: the shape of the card with stand-in bars, never any of
 *  their data (the stand-ins are identical for everyone), and one way to see the plans. */
function LockedMerchants() {
  return (
    <FinoraCard>
      <div className="mb-2 flex items-center justify-between gap-3">
        <SectionHeader title="Top merchants" size="sm" />
        <span className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-0.5 text-2xs text-muted">
          <Lock size={11} aria-hidden="true" /> Plus &amp; Premium
        </span>
      </div>
      <div className="space-y-3 opacity-50" aria-hidden="true" data-testid="locked-merchants-bars">
        {[92, 61, 40].map((w) => (
          <div key={w} className="h-2 overflow-hidden rounded-full bg-border"><div className="h-full rounded-full bg-muted" style={{ width: `${w}%` }} /></div>
        ))}
      </div>
      <p className="mt-3 text-sm text-ink">See which merchants took the most of your money.</p>
      <Link
        to="/app/billing" onClick={() => trackNavigation('subscription', 'contextual')}
        className="mt-2 inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 text-xs font-semibold text-on-primary hover:bg-primary-dark"
      >
        <Crown size={13} aria-hidden="true" /> View plans
      </Link>
    </FinoraCard>
  );
}

/** Top merchants for the month: real data with Advanced Reports, a locked preview without it. */
export function MerchantsCard({ month }: { month: string }) {
  return (
    <PremiumFeatureGate featureKey="ADVANCED_REPORTS" fallback={<LockedMerchants />}>
      <MerchantsList month={month} />
    </PremiumFeatureGate>
  );
}
