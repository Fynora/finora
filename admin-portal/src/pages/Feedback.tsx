import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { FilterBar } from '../components/FilterBar';
import { EntityDrawer } from '../components/EntityDrawer';
import { adminFeedbackApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { FeedbackBreakdownCount, FeedbackContext, FeedbackRow, FeedbackType } from '../types';

const PAGE_SIZE = 25;

const TYPE_OPTIONS: { label: string; value: FeedbackType }[] = [
  { label: 'Bug', value: 'BUG' },
  { label: 'Feature request', value: 'FEATURE_REQUEST' },
  { label: 'Improvement', value: 'IMPROVEMENT' },
  { label: 'General', value: 'GENERAL' },
];

const CONTEXT_OPTIONS: { label: string; value: FeedbackContext }[] = [
  { label: 'Dashboard', value: 'DASHBOARD' },
  { label: 'Transactions', value: 'TRANSACTIONS' },
  { label: 'Reports', value: 'REPORTS' },
  { label: 'Budgets', value: 'BUDGETS' },
  { label: 'Goals', value: 'GOALS' },
  { label: 'Import flow', value: 'IMPORT_FLOW' },
  { label: 'Accounts', value: 'ACCOUNTS' },
  { label: 'Settings', value: 'SETTINGS' },
  { label: 'Help', value: 'HELP' },
  { label: 'Other', value: 'OTHER' },
];

function typeLabel(type: FeedbackType): string {
  return TYPE_OPTIONS.find((t) => t.value === type)?.label ?? type;
}

function contextLabel(context: FeedbackContext): string {
  return CONTEXT_OPTIONS.find((c) => c.value === context)?.label ?? context;
}

/** Who sent it, for the table's "From" cell and the drawer. Name over email when both exist; when
 *  neither does (no user id on the entry, or the account row is gone) it says so rather than
 *  leaving a blank that reads like a failed load. */
function SenderCell({ row }: { row: FeedbackRow }) {
  if (!row.userName && !row.userEmail) {
    return <span className="text-muted text-xs">Unknown</span>;
  }
  return (
    <div className="min-w-0">
      {row.userName && <p className="text-ink text-sm truncate">{row.userName}</p>}
      {row.userEmail && <p className="text-muted text-xs truncate">{row.userEmail}</p>}
    </div>
  );
}

function DetailRow({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <p className="text-xs font-medium text-muted mb-0.5">{label}</p>
      <p className="text-sm text-ink break-words">{value || '—'}</p>
    </div>
  );
}

/** The drawer body. The message is the point of this page, so it leads, in full, with line breaks
 *  kept (a user's paragraphs are part of what they said) and long unbroken strings wrapped rather
 *  than pushed past the panel edge. */
function FeedbackDetail({ row }: { row: FeedbackRow }) {
  return (
    <div className="space-y-5">
      <div>
        <p className="text-xs font-medium text-muted mb-1">Message</p>
        <p className="text-sm text-ink whitespace-pre-wrap break-words">{row.message}</p>
      </div>
      <div className="border-t border-border pt-5 space-y-4">
        <DetailRow label="From" value={[row.userName, row.userEmail].filter(Boolean).join(' · ') || 'Unknown'} />
        <DetailRow label="Type" value={typeLabel(row.type)} />
        <DetailRow label="Context" value={contextLabel(row.context)} />
        <DetailRow label="Source" value={row.source.replace(/_/g, ' ')} />
        <DetailRow label="App version" value={row.appVersion} />
        <DetailRow label="Submitted" value={formatWhen(row.createdAt)} />
        <DetailRow label="User ID" value={row.userId} />
        <DetailRow label="Feedback ID" value={row.id} />
      </div>
    </div>
  );
}

/** One dimension's counts as horizontal bars, widest first -- countGrouped() already sorted them
 *  that way (see FeedbackDto.Breakdown.from's own doc comment), this just draws it. */
function BreakdownPanel({ title, counts }: { title: string; counts: FeedbackBreakdownCount[] }) {
  const max = counts.length > 0 ? counts[0].total : 0;
  return (
    <div className="bg-card border border-border rounded-xl2 p-6">
      <h3 className="text-sm font-semibold text-ink mb-3">{title}</h3>
      {counts.length === 0 ? (
        <p className="text-xs text-muted">No feedback yet.</p>
      ) : (
        <ul className="space-y-2">
          {counts.map((c) => (
            <li key={c.label} className="text-xs">
              <div className="flex justify-between mb-1">
                <span className="text-ink">{c.label.replace(/_/g, ' ')}</span>
                <span className="text-muted font-mono">{c.total}</span>
              </div>
              <div className="h-1.5 rounded-full bg-bg overflow-hidden">
                <div
                  className="h-full rounded-full bg-primary"
                  style={{ width: max > 0 ? `${(c.total / max) * 100}%` : '0%' }}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * Support, Help & Feedback v1, Phase 9. Proposal §3.4 / the plan's own words: "List. Filter.
 * Counts. Display breakdowns by: Type, Context, Source. No trend analysis. No AI clustering. No
 * dashboards." -- so the breakdown panel below is three static bar lists, not a chart library, and
 * there is deliberately no time axis anywhere on this page.
 */
function FeedbackContent() {
  const [page, setPage] = useState(0);
  const [type, setType] = useState<FeedbackType | ''>('');
  const [context, setContext] = useState<FeedbackContext | ''>('');
  const [selected, setSelected] = useState<FeedbackRow | null>(null);

  const list = useQuery({
    queryKey: ['feedback-list', page, type, context],
    queryFn: () => adminFeedbackApi.list({
      page,
      size: PAGE_SIZE,
      type: type || undefined,
      context: context || undefined,
    }),
  });
  // Always unfiltered -- see adminFeedbackApi.breakdown's own doc comment. Not refetched when
  // type/context change above; there is nothing for those filters to affect here.
  const breakdown = useQuery({
    queryKey: ['feedback-breakdown'],
    queryFn: () => adminFeedbackApi.breakdown(),
  });

  const columns: DataTableColumn<FeedbackRow>[] = [
    {
      header: 'From',
      render: (row) => <SenderCell row={row} />,
      cellClassName: 'max-w-[14rem]',
    },
    {
      header: 'Type',
      render: (row) => <span className="text-ink text-xs">{typeLabel(row.type)}</span>,
    },
    {
      header: 'Context',
      render: (row) => <span className="text-muted text-xs">{contextLabel(row.context)}</span>,
    },
    {
      header: 'Source',
      render: (row) => <span className="text-muted text-xs">{row.source.replace(/_/g, ' ')}</span>,
    },
    {
      header: 'Message',
      render: (row) => (
        // A button, not a click handler on the row: the table shell has no row-level click, and a
        // button is reachable by keyboard and announced as actionable. The full text also stays on
        // `title` as a hover preview, but the drawer is the way to actually read it.
        <button
          type="button"
          onClick={() => setSelected(row)}
          title={row.message}
          aria-label={`Open feedback: ${row.message.slice(0, 40)}`}
          className="text-left text-ink text-sm hover:text-primary"
        >
          {row.message.length > 80 ? `${row.message.slice(0, 80)}…` : row.message}
        </button>
      ),
    },
    { header: 'Submitted', render: (row) => formatWhen(row.createdAt) },
  ];

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <BreakdownPanel title={`By Type (${breakdown.data?.total ?? 0} total)`} counts={breakdown.data?.byType ?? []} />
        <BreakdownPanel title="By Context" counts={breakdown.data?.byContext ?? []} />
        <BreakdownPanel title="By Source" counts={breakdown.data?.bySource ?? []} />
      </div>

      <FilterBar
        fields={[
          {
            type: 'select', key: 'type', value: type,
            onChange: (v) => { setType(v as FeedbackType | ''); setPage(0); },
            placeholder: 'All types',
            label: 'Filter by type',
            options: TYPE_OPTIONS,
          },
          {
            type: 'select', key: 'context', value: context,
            onChange: (v) => { setContext(v as FeedbackContext | ''); setPage(0); },
            placeholder: 'All contexts',
            label: 'Filter by context',
            options: CONTEXT_OPTIONS,
          },
        ]}
      />

      <DataTable
        columns={columns}
        rows={list.data?.content ?? []}
        keyFor={(row) => row.id}
        loading={list.isLoading}
        emptyMessage="No feedback matches this filter."
      />

      <EntityDrawer
        open={selected !== null}
        onClose={() => setSelected(null)}
        title={selected ? `${typeLabel(selected.type)} feedback` : ''}
        subtitle={selected ? (selected.userName || selected.userEmail || 'Unknown sender') : undefined}
        tabs={selected ? [{ id: 'detail', label: 'Detail', content: <FeedbackDetail row={selected} /> }] : []}
      />

      {list.data && (
        <Pagination
          page={page}
          totalPages={list.data.totalPages}
          totalElements={list.data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
    </div>
  );
}

export default function Feedback() {
  return (
    <AdminLayout title="Feedback" subtitle="What users are telling us, unprompted.">
      <RequirePermission permission="SUPPORT_MANAGE">
        <FeedbackContent />
      </RequirePermission>
    </AdminLayout>
  );
}
