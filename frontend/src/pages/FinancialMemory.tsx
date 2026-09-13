import { useQuery } from '@tanstack/react-query';
import { Archive, Gauge, ListChecks, Receipt, Store, Wallet } from 'lucide-react';
import { workspaceApi } from '../api/endpoints';
import { MetricCard, Skeleton } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';

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
  const { data, isLoading } = useQuery({
    queryKey: ['workspace-dashboard'],
    queryFn: workspaceApi.dashboard,
    staleTime: 30_000,
  });
  const showSkeleton = useDelayedLoading(isLoading);

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
        </div>
      )}
    </div>
  );
}
