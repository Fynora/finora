import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { Sparkles, CheckCircle2, RotateCcw, TrendingUp, Percent, Wand2, Users, Undo2 } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { StatCard, type StatDelta } from '../components/StatCard';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { adminLearningApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type {
  LearningCategorySourceCount, LearningEventRow, LearningPlatformStatsDto, LearningWindow,
} from '../types';

const EVENTS_PAGE_SIZE = 25;

/** What each Transaction.DecisionSource means to an admin. An unknown value shows as itself. */
const SOURCE_LABELS: Record<string, string> = {
  LEARNED_PATTERN: 'Learned',
  GLOBAL_RULE: 'Global rule',
  USER_RULE: "Customer's own rule",
  KEYWORD_MATCH: 'Keyword',
  SHARED_CORPUS: 'Shared answers',
  AI_FALLBACK: 'AI',
  MANUAL: 'Set or changed by hand',
  FILE_PROVIDED: 'From the file',
  STRUCTURAL_P2P: 'Paid a person',
  MERCHANT_DEFAULT: 'Nothing matched',
};

const ACTION_LABELS: Record<string, { label: string; className: string }> = {
  LEARNED: { label: 'Learned', className: 'bg-success-bg text-success' },
  CORRECTED: { label: 'Corrected', className: 'bg-warning-bg text-warning' },
  UNDONE: { label: 'Undone', className: 'bg-info-bg text-info' },
  MERGED: { label: 'Merged', className: 'bg-info-bg text-info' },
  RESET: { label: 'Reset', className: 'bg-danger-bg text-danger' },
};

const axisTick = { fontSize: 11, fill: 'var(--color-muted)' };
const tooltipStyle = {
  background: 'var(--color-card)',
  border: '1px solid var(--color-border)',
  borderRadius: 8,
  fontSize: 12,
};

/** corrected / (learned + corrected), or null when there was nothing to correct. */
export function correctionRate(w: { learned: number; corrected: number }): number | null {
  const total = w.learned + w.corrected;
  return total === 0 ? null : w.corrected / total;
}

/** One decimal; a rate too small to show at one decimal reads "<0.1%", never "0.0%". */
export function formatPercent(rate: number | null): string {
  if (rate === null) return '—';
  const pct = rate * 100;
  return pct > 0 && pct < 0.05 ? '<0.1%' : `${pct.toFixed(1)}%`;
}

/** A falling correction rate is the good direction. */
function rateDelta(last: LearningWindow, previous: LearningWindow): StatDelta | undefined {
  const now = correctionRate(last);
  const before = correctionRate(previous);
  if (now === null || before === null) return undefined;
  const label = `${formatPercent(before)} in the 30 days before`;
  if (now === before) return { direction: 'flat', label, isGood: true };
  return { direction: now > before ? 'up' : 'down', label, isGood: now < before };
}

function sourceShare(sources: LearningCategorySourceCount[], source: string): number | null {
  const total = sources.reduce((sum, s) => sum + s.transactions, 0);
  if (total === 0) return null;
  return (sources.find((s) => s.source === source)?.transactions ?? 0) / total;
}

const count = (n: number) => n.toLocaleString('en-IN');

function SectionTitle({ children, note }: { children: string; note?: string }) {
  return (
    <div className="mb-3">
      <h2 className="text-sm font-semibold text-muted uppercase tracking-wide">{children}</h2>
      {note && <p className="text-xs text-muted mt-1">{note}</p>}
    </div>
  );
}

function Panel({ children }: { children: ReactNode }) {
  return <div className="bg-card border border-border rounded-xl2 p-5 shadow-card">{children}</div>;
}

function MonthlyCharts({ stats }: { stats: LearningPlatformStatsDto }) {
  const rates = stats.trend.map((p) => {
    const rate = correctionRate(p);
    return { month: p.month, rate: rate === null ? null : Math.round(rate * 1000) / 10 };
  });
  return (
    <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
      <div className="lg:col-span-3" data-testid="learning-monthly-chart">
        <p className="text-xs text-muted mb-2">Confirmations each month: the engine's guess kept, or corrected</p>
        <ResponsiveContainer width="100%" height={240}>
          <BarChart data={stats.trend} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid stroke="var(--color-border)" vertical={false} />
            <XAxis dataKey="month" tick={axisTick} axisLine={{ stroke: 'var(--color-border)' }} tickLine={false} />
            <YAxis allowDecimals={false} tick={axisTick} axisLine={false} tickLine={false} width={44} />
            <Tooltip contentStyle={tooltipStyle} cursor={{ fill: 'var(--color-border)', opacity: 0.4 }} />
            <Legend wrapperStyle={{ fontSize: 12 }} />
            <Bar dataKey="learned" name="Learned" stackId="m" fill="rgb(var(--color-primary))" maxBarSize={28} />
            <Bar dataKey="corrected" name="Corrected" stackId="m" fill="var(--color-warning)" maxBarSize={28}
              radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <div className="lg:col-span-2" data-testid="learning-rate-chart">
        <p className="text-xs text-muted mb-2">Correction rate each month (lower is better)</p>
        <ResponsiveContainer width="100%" height={240}>
          <LineChart data={rates} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            <CartesianGrid stroke="var(--color-border)" vertical={false} />
            <XAxis dataKey="month" tick={axisTick} axisLine={{ stroke: 'var(--color-border)' }} tickLine={false} />
            <YAxis tick={axisTick} axisLine={false} tickLine={false} width={44} tickFormatter={(v) => `${v}%`} />
            <Tooltip contentStyle={tooltipStyle} formatter={(v) => [`${v}%`, 'Correction rate']} />
            <Line type="monotone" dataKey="rate" name="Correction rate" stroke="var(--color-warning)"
              strokeWidth={2} dot={{ r: 3 }} connectNulls={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

function MonthlyTable({ stats }: { stats: LearningPlatformStatsDto }) {
  const columns: DataTableColumn<LearningPlatformStatsDto['trend'][number]>[] = [
    { header: 'Month', render: (p) => p.month },
    { header: 'Learned', render: (p) => count(p.learned), cellClassName: 'text-success tabular-nums' },
    { header: 'Corrected', render: (p) => count(p.corrected), cellClassName: 'text-warning tabular-nums' },
    { header: 'Correction rate', render: (p) => formatPercent(correctionRate(p)), cellClassName: 'tabular-nums' },
    { header: 'Undone', render: (p) => count(p.undone), cellClassName: 'text-muted tabular-nums' },
    { header: 'Resets', render: (p) => count(p.reset), cellClassName: 'text-muted tabular-nums' },
  ];
  return (
    <DataTable
      columns={columns}
      rows={[...stats.trend].reverse()}
      keyFor={(p) => p.month}
      loading={false}
      emptyMessage="No learning activity from customers yet."
    />
  );
}

function CategorySources({ sources }: { sources: LearningCategorySourceCount[] }) {
  const total = sources.reduce((sum, s) => sum + s.transactions, 0);
  if (total === 0) {
    return <p className="text-sm text-muted">No customer transactions were added in the last 30 days.</p>;
  }
  const max = Math.max(...sources.map((s) => s.transactions));
  return (
    <ul className="space-y-2" aria-label="How new transactions got their category">
      {sources.map((s) => {
        const learned = s.source === 'LEARNED_PATTERN';
        return (
          <li key={s.source} className="grid grid-cols-[9rem_1fr_7rem] sm:grid-cols-[11rem_1fr_8rem] items-center gap-3 text-sm">
            <span className={learned ? 'font-semibold text-ink' : 'text-ink'}>{SOURCE_LABELS[s.source] ?? s.source}</span>
            <span className="h-3 rounded bg-border overflow-hidden">
              <span
                className={`block h-full rounded ${learned ? 'bg-primary' : 'bg-muted'}`}
                style={{ width: `${(s.transactions / max) * 100}%` }}
              />
            </span>
            <span className="text-right tabular-nums text-muted">
              {formatPercent(s.transactions / total)} · {count(s.transactions)}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

function CorrectedMerchants({ stats }: { stats: LearningPlatformStatsDto }) {
  const rows = stats.topCorrectedMerchants;
  if (rows.length === 0) return <p className="text-sm text-muted">No corrections yet.</p>;
  const max = Math.max(...rows.map((r) => r.corrections));
  return (
    <ul className="space-y-3" aria-label="Most corrected merchants">
      {rows.map((m) => (
        <li key={m.merchant} className="text-sm">
          <div className="flex items-baseline justify-between gap-3">
            <span className="font-medium text-ink truncate">{m.merchant}</span>
            <span className="text-xs text-muted tabular-nums shrink-0">
              {count(m.corrections)} {m.corrections === 1 ? 'correction' : 'corrections'} · {count(m.users)} {m.users === 1 ? 'user' : 'users'}
            </span>
          </div>
          <span className="block h-2 mt-1 rounded bg-border overflow-hidden">
            <span className="block h-full rounded bg-warning" style={{ width: `${(m.corrections / max) * 100}%` }} />
          </span>
          {m.usualFrom && m.usualTo && (
            <p className="text-xs text-muted mt-1">Usually {m.usualFrom} → {m.usualTo}</p>
          )}
        </li>
      ))}
    </ul>
  );
}

function CategoryChanges({ stats }: { stats: LearningPlatformStatsDto }) {
  const rows = stats.topCategoryChanges;
  if (rows.length === 0) return <p className="text-sm text-muted">No corrections yet.</p>;
  return (
    <ul className="divide-y divide-border" aria-label="Most common category changes">
      {rows.map((c) => (
        <li key={`${c.fromCategory}\u0000${c.toCategory}`} className="flex justify-between gap-3 py-2 text-sm">
          <span className="text-ink">{c.fromCategory} → {c.toCategory}</span>
          <span className="text-muted tabular-nums">{count(c.count)}</span>
        </li>
      ))}
    </ul>
  );
}

/** A learned event names the category it confirmed (its "from" is the merchant's top category going
 *  in, often the same one). A correction shows the change. An undo, reset or merge names what the
 *  merchant had. A category deleted since reads as a dash. */
export function categoryChange(e: LearningEventRow): string {
  if (e.action === 'LEARNED') return e.toCategory ?? '—';
  if (e.action === 'CORRECTED') return `${e.fromCategory ?? '—'} → ${e.toCategory ?? '—'}`;
  if (e.fromCategory) return `Was ${e.fromCategory}`;
  return e.toCategory ?? '—';
}

function RecentEvents() {
  const [page, setPage] = useState(0);
  const events = useQuery({
    queryKey: ['admin-learning-events', page],
    queryFn: () => adminLearningApi.events({ page, size: EVENTS_PAGE_SIZE }),
  });
  const columns: DataTableColumn<LearningEventRow>[] = [
    { header: 'When', render: (e) => formatWhen(e.createdAt), cellClassName: 'text-muted whitespace-nowrap' },
    {
      header: 'Customer',
      render: (e) => <Link to={`/users/${e.userId}`} className="text-primary hover:underline">{e.userEmail}</Link>,
    },
    {
      header: 'Action',
      render: (e) => {
        const a = ACTION_LABELS[e.action] ?? { label: e.action, className: 'bg-info-bg text-info' };
        return <span className={`text-xs px-2 py-0.5 rounded ${a.className}`}>{a.label}</span>;
      },
    },
    { header: 'Merchant', render: (e) => e.merchant },
    { header: 'Category', render: categoryChange, cellClassName: 'text-muted' },
  ];
  return (
    <div>
      <DataTable
        columns={columns}
        rows={events.data?.content}
        keyFor={(e) => e.id}
        loading={events.isLoading}
        error={events.data ? null : events.error}
        emptyMessage="No learning events from customers yet."
      />
      {events.data && (
        <Pagination
          page={page}
          totalPages={events.data.totalPages}
          totalElements={events.data.totalElements}
          pageSize={EVENTS_PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
    </div>
  );
}

/**
 * Platform-wide Learning Engine: is the engine getting better, where does it get things wrong, and
 * who was affected. Customer accounts only. There's no self-service equivalent of this page
 * (MerchantLearningService.summary() is always scoped to CurrentUser). To see or act on one
 * account's learning, open that account from Users (read-only there too -- confirm/undo/reset stay
 * on the self-service Merchants console, see AdminUserLearningController's class comment).
 */
function LearningEngineContent() {
  const { data: stats, isLoading, error: loadError } = useQuery({
    queryKey: ['admin-learning-stats'],
    queryFn: () => adminLearningApi.platformStats(),
  });

  const placeholder = isLoading ? '…' : '—';
  const learnedShare = stats ? sourceShare(stats.categorySources, 'LEARNED_PATTERN') : null;
  const last30Rate = stats ? correctionRate(stats.last30Days) : null;

  return (
    <div className="space-y-8">
      <p className="text-sm text-muted max-w-2xl">
        Merchant category learning across every customer account (internal and admin accounts are
        left out). A correction is a customer overriding the category the engine had learned for a
        merchant, so a falling correction rate means the engine's guesses are getting better.
      </p>

      {loadError && !stats && (
        <p role="alert" className="text-sm text-danger">Couldn't load the learning figures. Try again in a moment.</p>
      )}

      <div className="space-y-4">
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <StatCard
            icon={Percent}
            label="Correction rate, 30 days"
            value={stats ? formatPercent(last30Rate) : placeholder}
            delta={stats ? rateDelta(stats.last30Days, stats.previous30Days) : undefined}
            hint={stats && last30Rate === null ? 'No confirmations in the last 30 days' : undefined}
          />
          <StatCard
            icon={Wand2}
            label="Sorted by learning, 30 days"
            value={stats ? formatPercent(learnedShare) : placeholder}
            hint="of transactions added"
          />
          <StatCard
            icon={Users}
            label="Customers with learning"
            value={stats ? count(stats.usersWithLearning) : placeholder}
            hint={stats && stats.usersWithLearning > 0
              ? `median ${stats.medianPairsPerUser.toLocaleString('en-IN', { maximumFractionDigits: 1 })} pairs each`
              : undefined}
          />
          <StatCard icon={Undo2} label="Undos" value={stats ? count(stats.undoneCount) : placeholder} hint="all time" />
        </div>
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <StatCard icon={Sparkles} label="Learned pairs" value={stats ? count(stats.learnedMerchantPairs) : placeholder} />
          <StatCard icon={CheckCircle2} label="Confirmations" value={stats ? count(stats.totalConfirmations) : placeholder} />
          <StatCard icon={TrendingUp} label="Corrections" value={stats ? count(stats.correctedCount) : placeholder} />
          <StatCard icon={RotateCcw} label="Resets" value={stats ? count(stats.resetCount) : placeholder} />
        </div>
      </div>

      {stats && (
        <section>
          <SectionTitle>Monthly learning</SectionTitle>
          {stats.trend.length === 0
            ? <Panel><p className="text-sm text-muted">No learning activity from customers yet.</p></Panel>
            : (
              <div className="space-y-4">
                <Panel><MonthlyCharts stats={stats} /></Panel>
                <MonthlyTable stats={stats} />
              </div>
            )}
        </section>
      )}

      {stats && (
        <section>
          <SectionTitle note="Transactions added in the last 30 days, by what picked their category. A category a customer set or changed counts as by hand.">
            How new transactions got their category
          </SectionTitle>
          <Panel><CategorySources sources={stats.categorySources} /></Panel>
        </section>
      )}

      {stats && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <section>
            <SectionTitle note="All time. The same merchant name in different accounts counts together.">
              Most corrected merchants
            </SectionTitle>
            <Panel><CorrectedMerchants stats={stats} /></Panel>
          </section>
          <section>
            <SectionTitle note="All time, by category name.">Most common category changes</SectionTitle>
            <Panel><CategoryChanges stats={stats} /></Panel>
          </section>
        </div>
      )}

      <section>
        <SectionTitle note="Newest first. Open a customer to see their own learning.">Recent learning events</SectionTitle>
        <RecentEvents />
      </section>
    </div>
  );
}

export default function LearningEngine() {
  return (
    <AdminLayout title="Learning Engine" subtitle="Platform-wide merchant category learning, aggregated across every customer">
      <RequirePermission permission="MERCHANT_MANAGE">
        <LearningEngineContent />
      </RequirePermission>
    </AdminLayout>
  );
}
