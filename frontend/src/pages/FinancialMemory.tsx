import { useQuery } from '@tanstack/react-query';
import { Archive, Gauge, ListChecks, PenLine, Receipt, Repeat, Store, Wallet } from 'lucide-react';
import { workspaceApi, recurringApi } from '../api/endpoints';
import { MetricCard, EmptyState, FinoraCard, Badge, Skeleton } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';

// Same formatting convention Dashboard.tsx/Ledger.tsx each already carry their own copy of --
// negative amounts must render as "-₹500", not "₹-500" (string concatenation put the currency
// symbol before the sign).
function fmt(n: number) {
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

// Financial Memory Completeness Dashboard (issue #1450). Deliberately plain: the conversion-
// psychology framework behind this page (docs referenced in the issue) calls for a factual
// report of a real asset, not a gamified progress bar -- no badges, no confetti, no color-coded
// "achievement" styling. Every number here is either a straight count from the backend or
// completenessPercent, which is honestly allowed to read below 100% (see
// FinancialMemoryCompleteness's backend doc comment) rather than dressed up to always look good.
function formatMonths(months: number | null): string {
  if (months === null) return '—';
  if (months < 12) return `${months} month${months === 1 ? '' : 's'}`;
  const years = Math.floor(months / 12);
  const remainder = months % 12;
  if (remainder === 0) return `${years} year${years === 1 ? '' : 's'}`;
  return `${years}y ${remainder}m`;
}

export default function FinancialMemory() {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['workspace-dashboard'],
    queryFn: workspaceApi.dashboard,
    staleTime: 30_000,
  });
  const showSkeleton = useDelayedLoading(isLoading);

  // Issue #1452. Independent of the summary query above -- same principle Insights.tsx's
  // insightsFailed/recurringError split already follows for this identical pair of endpoints: one
  // failing must not take the other down.
  const recurringQ = useQuery({
    queryKey: ['recurring'],
    queryFn: recurringApi.list,
    staleTime: 30_000,
    retry: false,
  });
  const showRecurringSkeleton = useDelayedLoading(recurringQ.isLoading);

  return (
    <div className="space-y-4">
      <div className="max-w-xl">
        <p className="text-2xs font-semibold uppercase tracking-widest text-muted mb-1">Financial Memory</p>
        <h1 className="text-2xl md:text-3xl font-bold text-ink font-display">What Fynora remembers</h1>
        <p className="text-sm text-muted mt-1">
          A factual record of how much of your financial history Fynora has built up so far.
        </p>
      </div>

      {showSkeleton ? (
        <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
          {Array.from({ length: 6 }, (_, i) => <Skeleton.Card key={i} />)}
        </div>
      ) : isError || !data ? (
        // Bug fix: this used to fall straight through to the metric grid below with `data`
        // undefined, rendering every card as a false "0" -- a fetch failure looking identical
        // to a genuinely brand-new account with nothing imported yet. Same pattern
        // Dashboard.tsx's KPI cards already use for the same failure mode.
        <p className="text-sm text-muted">
          {(error as any)?.response?.data?.message ?? "Couldn't load your financial memory — please try again later."}
        </p>
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
          <MetricCard
            label="History"
            value={formatMonths(data?.monthsOfHistory ?? null)}
            icon={Archive}
            iconBg="bg-primary/10"
            iconColor="text-primary"
            caption="since your first statement"
          />
          <MetricCard
            label="Completeness"
            value={data?.completenessPercent != null ? `${data.completenessPercent}%` : '—'}
            icon={Gauge}
            iconBg="bg-primary/10"
            iconColor="text-primary"
            caption="months covered, no gaps"
          />
          <MetricCard
            label="Accounts connected"
            value={String(data?.totalAccounts ?? 0)}
            icon={Wallet}
            iconBg="bg-primary/10"
            iconColor="text-primary"
          />
          <MetricCard
            label="Transactions processed"
            value={(data?.totalTransactions ?? 0).toLocaleString('en-IN')}
            icon={Receipt}
            iconBg="bg-primary/10"
            iconColor="text-primary"
          />
          <MetricCard
            label="Merchants identified"
            value={String(data?.totalMerchants ?? 0)}
            icon={Store}
            iconBg="bg-primary/10"
            iconColor="text-primary"
            caption={`${data?.learnedMerchants ?? 0} learned`}
          />
          <MetricCard
            label="Rules learned"
            value={String(data?.activeRules ?? 0)}
            icon={ListChecks}
            iconBg="bg-primary/10"
            iconColor="text-primary"
          />
          <MetricCard
            label="Manual corrections"
            value={String(data?.totalManualCorrections ?? 0)}
            icon={PenLine}
            iconBg="bg-primary/10"
            iconColor="text-primary"
            caption="auto-categorized imports you've corrected"
          />
        </div>
      )}

      <div className="max-w-xl">
        <h2 className="font-semibold text-ink">Recognized recurring payments</h2>
        <p className="text-sm text-muted mt-1">
          Subscriptions and regular payments Fynora has spotted from your transaction history.
        </p>
      </div>

      {showRecurringSkeleton ? (
        <Skeleton.Card />
      ) : recurringQ.isError ? (
        <p className="text-sm text-muted">Couldn't load your recurring payments — please try again later.</p>
      ) : (recurringQ.data ?? []).length === 0 ? (
        <FinoraCard padding="sm">
          <EmptyState
            icon={Repeat}
            iconBg="bg-primary/10"
            iconColor="text-primary"
            title="No recurring payments recognized yet"
            desc="This needs at least 2 charges from the same merchant with a regular interval to spot a pattern."
          />
        </FinoraCard>
      ) : (
        <FinoraCard padding="sm">
          <ul className="divide-y divide-border">
            {(recurringQ.data ?? []).map((r) => (
              <li key={r.merchant} className="flex items-center justify-between py-2.5 text-sm">
                <span className="text-ink font-medium">{r.merchant} <Badge label={r.label} className="ml-1" /></span>
                <span className="text-muted">{fmt(r.averageAmount)}</span>
              </li>
            ))}
          </ul>
        </FinoraCard>
      )}
    </div>
  );
}
