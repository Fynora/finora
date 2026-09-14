import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Bar, Line } from 'react-chartjs-2';
import {
  Chart as ChartJS, BarElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler,
} from 'chart.js';
import { Crown, Lock, Store, Tags, Brain, TrendingUp as TrendingUpIcon } from 'lucide-react';
import { analyticsApi, reportsApi } from '../api/endpoints';
import { FinoraCard, EmptyState, SectionHeader, ChartContainer, baseChartOptions, Skeleton, useChartColors } from '../design-system';
import { PremiumFeatureGate } from '../components/PremiumFeatureGate';

ChartJS.register(BarElement, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler);

function fmt(n: number) {
  // Negative amounts render as "-₹500", not "₹-500" -- same convention as every other page's fmt.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

function monthLabel(monthStr: string) {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
}

function monthLabelLong(monthStr: string) {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
}

/** The upgrade prompt shown in place of the whole page for a Free user -- more context than
 *  PremiumFeatureGate's own generic default, since this gates an entire page rather than one
 *  widget. Fails closed exactly like the default (PremiumFeatureGate renders nothing at all while
 *  the entitlements query is loading or erroring; this only ever appears once it has confirmed
 *  the feature is absent). */
function UpgradePrompt() {
  return (
    <FinoraCard padding="lg" className="max-w-lg mx-auto my-12">
      <EmptyState
        icon={Lock}
        iconBg="bg-primary-light"
        iconColor="text-primary"
        title="Advanced Reports is a Plus & Premium feature"
        desc="Top merchants, spend trends, category confidence, and how the categorization engine is learning your habits -- all built from your own transaction history."
        cta={
          <Link
            to="/app/billing"
            className="inline-flex items-center gap-1.5 bg-primary text-on-primary hover:bg-primary-dark rounded-lg px-4 py-2 text-xs font-semibold"
          >
            <Crown size={14} /> View plans
          </Link>
        }
      />
    </FinoraCard>
  );
}

/** A "top N" bar list -- label, a bar sized relative to the largest value in THIS list (not a
 *  running total), and the amount. Same visual language as Reports.tsx's Category Breakdown
 *  rows, adapted for a ranked list rather than a share-of-total. */
function RankedBarList({
  rows, empty,
}: {
  rows: { label: string; sub: string; value: number }[];
  empty: { icon: typeof Store; title: string; desc: string };
}) {
  if (rows.length === 0) {
    return <EmptyState icon={empty.icon} iconBg="bg-primary-light" iconColor="text-primary" title={empty.title} desc={empty.desc} />;
  }
  const max = Math.max(...rows.map((r) => r.value), 1);
  return (
    <div className="space-y-3">
      {rows.map((r) => (
        <div key={r.label} className="grid grid-cols-[1fr_90px] items-center gap-3 text-sm">
          <div className="min-w-0">
            <div className="flex items-baseline justify-between gap-2">
              <span className="text-ink font-medium truncate">{r.label}</span>
              <span className="text-2xs text-muted flex-shrink-0">{r.sub}</span>
            </div>
            <div className="h-1.5 bg-black/10 rounded overflow-hidden mt-1">
              <div className="h-full bg-primary" style={{ width: `${(r.value / max) * 100}%` }} />
            </div>
          </div>
          <span className="text-right font-medium">{fmt(r.value)}</span>
        </div>
      ))}
    </div>
  );
}

function ListSkeleton() {
  return (
    <div className="space-y-3">
      {Array.from({ length: 5 }, (_, i) => (
        <div key={i} className="grid grid-cols-[1fr_90px] items-center gap-3">
          <Skeleton.Block className="h-6 w-full" />
          <Skeleton.Text width="w-full" className="h-2.5" />
        </div>
      ))}
    </div>
  );
}

/** The gated content -- only ever rendered once PremiumFeatureGate has confirmed ADVANCED_REPORTS
 *  is granted, so none of these queries fire for a Free user. */
function AdvancedReportsContent() {
  const [month, setMonth] = useState<string>(''); // '' = all-time
  const colors = useChartColors();

  const monthsQ = useQuery({ queryKey: ['report-months'], queryFn: () => reportsApi.availableMonths() });
  const topMerchantsQ = useQuery({
    queryKey: ['advanced-reports-top-merchants', month],
    queryFn: () => analyticsApi.topMerchants(month || undefined),
  });
  const topCategoriesQ = useQuery({
    queryKey: ['advanced-reports-top-categories', month],
    queryFn: () => analyticsApi.topCategories(month || undefined),
  });
  const trendQ = useQuery({ queryKey: ['advanced-reports-trend'], queryFn: () => analyticsApi.trend() });
  const confidenceQ = useQuery({ queryKey: ['advanced-reports-confidence'], queryFn: () => analyticsApi.categoryConfidence() });
  const learningQ = useQuery({ queryKey: ['advanced-reports-learning-growth'], queryFn: () => analyticsApi.learningGrowth() });
  const [comparisonMode, setComparisonMode] = useState<'full' | 'ytd'>('full');
  const multiYearIncomeQ = useQuery({ queryKey: ['multi-year-income'], queryFn: () => analyticsApi.multiYearIncome() });
  const multiYearSpendQ = useQuery({ queryKey: ['multi-year-spend'], queryFn: () => analyticsApi.multiYearSpend() });
  const multiYearLifestyleQ = useQuery({ queryKey: ['multi-year-lifestyle'], queryFn: () => analyticsApi.multiYearLifestyleInflation() });

  const months = monthsQ.data ?? [];

  return (
    <div className="space-y-6">
      <FinoraCard padding="sm" className="flex flex-wrap items-end gap-3 justify-between">
        <div>
          <label htmlFor="advanced-reports-month" className="block text-xs uppercase text-muted mb-1">Period</label>
          <select
            id="advanced-reports-month"
            value={month}
            onChange={(e) => setMonth(e.target.value)}
            className="bg-card text-ink border rounded px-2 py-1.5 text-sm"
          >
            <option value="">All time</option>
            {[...months].reverse().map((m) => <option key={m} value={m}>{monthLabelLong(m)}</option>)}
          </select>
        </div>
        <p className="text-2xs text-muted max-w-xs">Applies to Top Merchants and Top Categories below. Spend Trend, Category Confidence and Learning Growth always cover your full history.</p>
      </FinoraCard>

      <div className="grid lg:grid-cols-2 gap-6">
        <FinoraCard padding="lg">
          <SectionHeader title="Top Merchants" />
          {topMerchantsQ.isLoading ? <ListSkeleton /> : (
            <RankedBarList
              rows={(topMerchantsQ.data ?? []).map((m) => ({ label: m.merchantName, sub: `${m.transactionCount} txns`, value: m.totalSpend }))}
              empty={{ icon: Store, title: 'No merchant spend yet', desc: 'Import a statement or add transactions to see your top merchants.' }}
            />
          )}
        </FinoraCard>

        <FinoraCard padding="lg">
          <SectionHeader title="Top Categories" />
          {topCategoriesQ.isLoading ? <ListSkeleton /> : (
            <RankedBarList
              rows={(topCategoriesQ.data ?? []).map((c) => ({ label: c.categoryName, sub: `${c.transactionCount} txns`, value: c.totalSpend }))}
              empty={{ icon: Tags, title: 'No categorized spend yet', desc: 'Your top spending categories will appear here.' }}
            />
          )}
        </FinoraCard>
      </div>

      <FinoraCard padding="lg">
        <SectionHeader title="Spend Trend" />
        <p className="text-xs text-muted -mt-2 mb-4">Merchant-attributed spend over your trailing 6 months.</p>
        <ChartContainer
          height={260}
          loading={trendQ.isLoading}
          loadingLabel="Loading spend trend"
          isEmpty={(trendQ.data ?? []).every((p) => p.totalSpend === 0)}
          emptyState={
            <EmptyState icon={TrendingUpIcon} iconBg="bg-primary-light" iconColor="text-primary" title="No trend yet" desc="Once you have a few months of spend, the trend appears here." />
          }
        >
          <Line
            data={{
              labels: (trendQ.data ?? []).map((p) => monthLabel(p.month)),
              datasets: [{
                label: 'Spend', data: (trendQ.data ?? []).map((p) => p.totalSpend),
                borderColor: colors.blue, backgroundColor: colors.blue + '14', fill: true, tension: 0.3,
              }],
            }}
            options={{ ...baseChartOptions, scales: { y: { ticks: { callback: (v) => fmt(Number(v)) } } } }}
          />
        </ChartContainer>
      </FinoraCard>

      <FinoraCard padding="lg">
        <SectionHeader title="Multi-Year Comparison" />
        <p className="text-xs text-muted -mt-2 mb-4">Income, spend, and how much of your income spend is eating, year over year.</p>
        <div className="flex gap-2 mb-4">
          <button
            className={`text-xs px-3 py-1 rounded ${comparisonMode === 'full' ? 'bg-primary text-white' : 'bg-card border'}`}
            onClick={() => setComparisonMode('full')}
          >
            Full Years
          </button>
          <button
            className={`text-xs px-3 py-1 rounded ${comparisonMode === 'ytd' ? 'bg-primary text-white' : 'bg-card border'}`}
            onClick={() => setComparisonMode('ytd')}
          >
            This Year So Far
          </button>
        </div>
        <ChartContainer
          height={260}
          loading={multiYearIncomeQ.isLoading || multiYearSpendQ.isLoading}
          loadingLabel="Loading multi-year comparison"
          isEmpty={
            comparisonMode === 'full'
              ? (multiYearIncomeQ.data?.fullYears ?? []).length === 0
              : (multiYearIncomeQ.data?.thisYearSoFar.years ?? []).length === 0
          }
          emptyState={
            <EmptyState
              icon={TrendingUpIcon}
              iconBg="bg-primary-light"
              iconColor="text-primary"
              title="Not enough history yet"
              desc={
                comparisonMode === 'full'
                  ? 'Once you have a full calendar year of data, it appears here.'
                  : 'This mode compares the same months across years -- once a prior year fully covers the months you have so far this year, it appears here.'
              }
            />
          }
        >
          <Bar
            data={{
              labels: comparisonMode === 'full'
                ? (multiYearIncomeQ.data?.fullYears ?? []).map((p) => String(p.year))
                : (multiYearIncomeQ.data?.thisYearSoFar.years ?? []).map((p) => String(p.year)),
              datasets: [
                {
                  label: 'Income',
                  data: comparisonMode === 'full'
                    ? (multiYearIncomeQ.data?.fullYears ?? []).map((p) => p.total)
                    : (multiYearIncomeQ.data?.thisYearSoFar.years ?? []).map((p) => p.total),
                  backgroundColor: colors.success,
                },
                {
                  label: 'Spend',
                  data: comparisonMode === 'full'
                    ? (multiYearSpendQ.data?.fullYears ?? []).map((p) => p.total)
                    : (multiYearSpendQ.data?.thisYearSoFar.years ?? []).map((p) => p.total),
                  backgroundColor: colors.orange,
                },
              ],
            }}
            options={{ ...baseChartOptions, scales: { y: { ticks: { callback: (v) => fmt(Number(v)) } } } }}
          />
        </ChartContainer>
        {/* Coverage is rendered as real, visible text -- not left inside the chart's own labels
            -- so a partial year's badge is unavoidable, not a hover-only or canvas-only detail. */}
        {comparisonMode === 'full' && (
          <ul className="text-xs text-muted mt-2 space-y-0.5">
            {(multiYearIncomeQ.data?.fullYears ?? []).map((p) => (
              <li key={p.year}>{p.year}: {p.isComplete ? 'full year' : `${p.coverageMonths}/12 months`}</li>
            ))}
          </ul>
        )}
      </FinoraCard>

      {multiYearLifestyleQ.data && multiYearLifestyleQ.data.fullYears.length > 0 && (
        <FinoraCard padding="lg">
          <SectionHeader title="Lifestyle Inflation" />
          <p className="text-xs text-muted -mt-2 mb-4">Spend as a share of income, per year — a rising number means spend is growing faster than income.</p>
          <ul className="text-sm space-y-1">
            {multiYearLifestyleQ.data.fullYears.map((p) => (
              <li key={p.year} className="flex justify-between">
                <span>{p.year}{!p.isComplete && ` (${p.coverageMonths}/12 months)`}</span>
                <span>{p.ratio === null ? '—' : `${(p.ratio * 100).toFixed(0)}%`}</span>
              </li>
            ))}
          </ul>
        </FinoraCard>
      )}

      <div className="grid lg:grid-cols-2 gap-6">
        <FinoraCard padding="lg">
          <SectionHeader title="Category Confidence" />
          <p className="text-xs text-muted -mt-2 mb-4">How sure the categorization engine is about each category, on average, across your merchants.</p>
          <ChartContainer
            height={260}
            loading={confidenceQ.isLoading}
            loadingLabel="Loading category confidence"
            isEmpty={(confidenceQ.data ?? []).length === 0}
            emptyState={
              <EmptyState icon={Brain} iconBg="bg-primary-light" iconColor="text-primary" title="Nothing learned yet" desc="Confirm a few categorizations and this fills in." />
            }
          >
            <Bar
              data={{
                labels: (confidenceQ.data ?? []).map((c) => c.category),
                datasets: [{ label: 'Avg. confidence', data: (confidenceQ.data ?? []).map((c) => c.avgConfidence), backgroundColor: colors.blue }],
              }}
              options={{ ...baseChartOptions, scales: { y: { min: 0, max: 100, ticks: { callback: (v) => `${v}%` } } } }}
            />
          </ChartContainer>
        </FinoraCard>

        <FinoraCard padding="lg">
          <SectionHeader title="Learning Growth" />
          <p className="text-xs text-muted -mt-2 mb-4">Categorizations the engine learned on its own vs. ones you corrected, per month.</p>
          <ChartContainer
            height={260}
            loading={learningQ.isLoading}
            loadingLabel="Loading learning growth"
            isEmpty={(learningQ.data ?? []).length === 0}
            emptyState={
              <EmptyState icon={Brain} iconBg="bg-primary-light" iconColor="text-primary" title="No learning history yet" desc="This fills in as you confirm categorizations over time." />
            }
          >
            <Bar
              data={{
                labels: (learningQ.data ?? []).map((p) => monthLabel(p.month)),
                datasets: [
                  { label: 'Learned', data: (learningQ.data ?? []).map((p) => p.learnedCount), backgroundColor: colors.success },
                  { label: 'Corrected', data: (learningQ.data ?? []).map((p) => p.correctedCount), backgroundColor: colors.orange },
                ],
              }}
              options={baseChartOptions}
            />
          </ChartContainer>
        </FinoraCard>
      </div>
    </div>
  );
}

export default function AdvancedReports() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold text-ink flex items-center gap-2">
          <Crown size={18} className="text-primary" /> Advanced Reports
        </h1>
        <p className="text-sm text-muted mt-0.5">Deeper analysis of your spending, built from the same engine behind your Dashboard.</p>
      </div>
      <PremiumFeatureGate featureKey="ADVANCED_REPORTS" fallback={<UpgradePrompt />}>
        <AdvancedReportsContent />
      </PremiumFeatureGate>
    </div>
  );
}
