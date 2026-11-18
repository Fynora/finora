import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Tag, Store, Users, IndianRupee, ListChecks, Receipt } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { StatCard, type StatDelta } from '../components/StatCard';
import { adminPlatformAnalyticsApi } from '../api/endpoints';
import type { PlatformSpendRowDto, PlatformSpendSummaryDto } from '../types';
import { changePercent, periodChoices, rupees, signed, spanLabel } from '../lib/platformPeriod';

/** Share of spend still to sort, whole percent; null when the money figures are withheld. */
function toSortShare(s: PlatformSpendSummaryDto | null | undefined): number | null {
  if (!s || s.totalSpend == null || s.toSortSpend == null || s.totalSpend <= 0) return null;
  return Math.round((s.toSortSpend / s.totalSpend) * 100);
}

function delta(change: number, unit: string, vs: string, upIsGood: boolean): StatDelta {
  return {
    direction: change > 0 ? 'up' : change < 0 ? 'down' : 'flat',
    label: `${signed(change)}${unit} vs ${vs}`,
    isGood: change > 0 ? upIsGood : !upIsGood,
  };
}

function SpendTable({ title, nameHeader, icon: Icon, rows, loading, error, vs, minCustomers, emptyMessage }: {
  title: string;
  nameHeader: string;
  icon: typeof Tag;
  rows: PlatformSpendRowDto[] | undefined;
  loading: boolean;
  error: unknown;
  vs: string | null;
  minCustomers: number;
  emptyMessage: string;
}) {
  const columns: DataTableColumn<PlatformSpendRowDto>[] = [
    {
      header: nameHeader,
      render: (r) => (
        <div className="flex items-center gap-2.5">
          <span className="w-7 h-7 rounded-lg bg-bg border border-border flex items-center justify-center flex-shrink-0">
            <Icon size={13} className="text-muted" />
          </span>
          <span className="font-medium text-ink">{r.name}</span>
        </div>
      ),
    },
    { header: 'Customers', render: (r) => r.customers, cellClassName: 'text-ink' },
    { header: 'Spend', render: (r) => rupees(r.totalSpend), cellClassName: 'text-muted' },
    { header: 'Per customer', render: (r) => rupees(r.medianSpendPerCustomer), cellClassName: 'text-muted' },
    { header: 'Transactions', render: (r) => r.transactionCount, cellClassName: 'text-muted' },
  ];
  if (vs) {
    columns.push({
      // Per customer, not the total: a total rises whenever more customers join or import.
      header: `Per customer vs ${vs}`,
      render: (r) => {
        const change = changePercent(r.medianSpendPerCustomer, r.previousMedianSpendPerCustomer);
        return change == null
          ? <span title={`Fewer than ${minCustomers} customers spent here then`}>—</span>
          : `${signed(change)}%`;
      },
      cellClassName: 'text-muted',
    });
  }
  return (
    <section>
      <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">
        {title} <span className="normal-case font-normal">· by number of customers</span>
      </h2>
      <DataTable columns={columns} rows={rows} keyFor={(r) => r.name} loading={loading} error={error} emptyMessage={emptyMessage} />
    </section>
  );
}

/**
 * Platform-wide spend for one period: who spends, on what, and how much is still to sort. Amounts
 * are each customer's own report figures (see AdminPlatformAnalyticsService). Rows are ranked by
 * how many customers spent under a name, and a name fewer than 3 customers spent under is not
 * listed, so the page does not point at one or two people.
 */
function PlatformAnalyticsContent() {
  const choices = useMemo(() => periodChoices(new Date()), []);
  const [key, setKey] = useState(choices[0].key);
  const choice = choices.find((c) => c.key === key) ?? choices[0];

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['admin-platform-analytics', choice.key],
    queryFn: () => adminPlatformAnalyticsApi.get(choice.period),
    placeholderData: keepPreviousData,
    // Each load reads every customer's rows, so returning to the tab should not repeat it.
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  });
  // A failed refresh keeps the figures already on screen; only a load with nothing to show is an error.
  const loadError = data ? null : error;

  const vs = data?.previousFrom && data.previousTo ? spanLabel(data.previousFrom, data.previousTo) : null;
  const span = data?.from && data.to ? spanLabel(data.from, data.to) : null;
  const summary = data?.summary;
  const previous = data?.previousSummary;
  const withheld = summary != null && summary.totalSpend == null;
  const share = toSortShare(summary);
  const previousShare = toSortShare(previous);
  const minCustomers = data?.minCustomers ?? 3;
  const loadingValue = '…';

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-xs text-muted flex flex-col gap-1">
          Period
          <select
            value={choice.key}
            onChange={(e) => setKey(e.target.value)}
            className="border border-border rounded-lg px-2 py-1 text-sm text-ink bg-card"
          >
            {choices.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}
          </select>
        </label>
        <p className="text-sm text-muted pb-1">
          {span && vs ? `${span}, compared with ${vs}.` : choice.period ? '' : 'All time has nothing to compare with.'}
          {isFetching && !isLoading ? ' Updating…' : ''}
        </p>
      </div>

      <div className="space-y-3">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <StatCard
            icon={Users}
            label="Customers with spend"
            value={isLoading ? loadingValue : summary?.customersWithSpend ?? '—'}
            delta={summary && previous && vs
              ? delta(summary.customersWithSpend - previous.customersWithSpend, '', vs, true) : undefined}
          />
          <StatCard
            icon={IndianRupee}
            label="Spend per customer"
            value={isLoading ? loadingValue : summary?.medianSpendPerCustomer != null ? rupees(summary.medianSpendPerCustomer) : '—'}
          />
          <StatCard
            icon={ListChecks}
            label="Spend still to sort"
            value={isLoading ? loadingValue : share != null ? `${share}%` : '—'}
            delta={share != null && previousShare != null && vs ? delta(share - previousShare, ' pts', vs, false) : undefined}
          />
          <StatCard
            icon={Receipt}
            label="Expense transactions"
            value={isLoading ? loadingValue : summary ? summary.transactionCount.toLocaleString('en-IN') : '—'}
          />
        </div>
        <p className="text-sm text-muted max-w-3xl">
          Spend per customer is the middle customer&apos;s total for the period. Still to sort is the share of
          spend with no category or flagged for review, the same flag customers see as &quot;needs review&quot;.
          Amounts match each customer&apos;s own reports: refunds and shared costs are taken off, and duplicates,
          transfers, card bill payments and replaced statements are left out. Investments are not in these totals.
          Staff accounts are not counted. Recent months can rise as customers import more statements.
          {withheld && ` Money figures are hidden because fewer than ${minCustomers} customers spent in this period.`}
        </p>
      </div>

      <SpendTable
        title="Top categories"
        nameHeader="Category"
        icon={Tag}
        rows={data?.topCategories}
        loading={isLoading}
        error={loadError}
        vs={vs}
        minCustomers={minCustomers}
        emptyMessage={`No category had spend from ${minCustomers} or more customers in this period.`}
      />

      <SpendTable
        title="Top merchants"
        nameHeader="Merchant"
        icon={Store}
        rows={data?.topMerchants}
        loading={isLoading}
        error={loadError}
        vs={vs}
        minCustomers={minCustomers}
        emptyMessage={`No merchant had spend from ${minCustomers} or more customers in this period.`}
      />

      <p className="text-xs text-muted">
        A category or merchant fewer than {minCustomers} customers spent under is not listed, so this page does not
        point at one or two people. How users tracked spending before Fynora is now on{' '}
        <Link to="/analytics/activation" className="underline hover:text-ink">Activation</Link>.
      </p>
    </div>
  );
}

export default function PlatformAnalytics() {
  return (
    <AdminLayout title="Platform Analytics" subtitle="What customers spend on, by period">
      <RequirePermission permission="PLATFORM_ANALYTICS_VIEW">
        <PlatformAnalyticsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
