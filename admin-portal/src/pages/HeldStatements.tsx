import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { FilterBar } from '../components/FilterBar';
import { adminHeldStatementApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { HeldStatementRow, HeldStatementStatus, HoldWithoutReviewRecordRow } from '../types';

const PAGE_SIZE = 25;

const STATUS_OPTIONS: { label: string; value: HeldStatementStatus }[] = [
  { label: 'Held', value: 'HELD' },
  { label: 'Assigned', value: 'ASSIGNED' },
  { label: 'Investigating', value: 'INVESTIGATING' },
  { label: 'Ready for import', value: 'READY_FOR_IMPORT' },
];

const RESOLVED_STATUS_OPTIONS: { label: string; value: HeldStatementStatus }[] = [
  { label: 'Imported', value: 'IMPORTED' },
  { label: 'Rejected', value: 'REJECTED' },
];

type View = 'open' | 'resolved';

function actionErrorMessage(error: unknown): string {
  const response = (error as { response?: { data?: { message?: string } } })?.response;
  return response?.data?.message ?? 'That action could not be completed.';
}

/**
 * Imports the worker held for trust review without writing a review record -- it holds them anyway
 * when that write fails, so their rows stay off the ledger, but nothing in the queue below stands
 * for them and the user waits on a review nobody can see. Opening one writes the record; the import
 * then joins the queue and is released or rejected like any other, from its detail page, with its
 * evidence. Renders nothing while there are none, which is the normal state.
 */
function HoldsWithoutReviewRecord() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const stuck = useQuery({
    queryKey: ['held-statements-without-review-record'],
    queryFn: () => adminHeldStatementApi.listWithoutReviewRecord(0, PAGE_SIZE),
  });
  const openReview = useMutation({
    mutationFn: (jobId: string) => adminHeldStatementApi.openReview(jobId),
    onSuccess: (held) => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['held-statements-without-review-record'] });
      void queryClient.invalidateQueries({ queryKey: ['held-statements-list'] });
      void navigate(`/held-statements/${held.heldId}`);
    },
    // A second operator opening the same one gets a 409 -- refresh so the row disappears.
    onError: (e) => {
      setError(actionErrorMessage(e));
      void queryClient.invalidateQueries({ queryKey: ['held-statements-without-review-record'] });
    },
  });

  const rows = stuck.data?.content ?? [];
  if (rows.length === 0) return null;

  const columns: DataTableColumn<HoldWithoutReviewRecordRow>[] = [
    { header: 'File', render: (row) => <span className="text-ink">{row.fileName ?? '—'}</span> },
    { header: 'Held At', render: (row) => (row.heldAt ? formatWhen(row.heldAt) : '—') },
    {
      header: 'Staged rows',
      render: (row) => (
        <span className="text-muted text-xs">
          {row.stagedRowsAvailable ? 'Available' : 'Gone — re-run the parser after opening'}
        </span>
      ),
    },
    {
      header: '',
      // Already covered by another upload's review on the same rows: decided there, not here --
      // a second review would block the rows until both were approved.
      render: (row) => row.coveredByHeldId ? (
        <span className="text-muted text-xs">
          Covered by{' '}
          <Link to={`/held-statements/${row.coveredByHeldId}`} className="font-mono text-primary hover:underline">
            {row.coveredByHeldId}
          </Link>
        </span>
      ) : (
        <button
          type="button"
          onClick={() => openReview.mutate(row.jobId)}
          disabled={openReview.isPending}
          className="rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-on-primary hover:bg-primary-dark disabled:opacity-50"
        >
          Open review
        </button>
      ),
    },
  ];

  return (
    <section aria-labelledby="holds-without-review-record" className="space-y-3 rounded-lg border border-border p-4">
      <h2 id="holds-without-review-record" className="text-ink text-sm font-semibold">
        Held without a review record ({stuck.data?.totalElements ?? rows.length})
      </h2>
      <p className="text-muted text-sm">
        These imports were held, but their review could not be recorded, so they are not in the
        queue below and their users cannot confirm them. Open a review to move one into the queue,
        then release or reject it there.
      </p>
      {(stuck.data?.totalElements ?? 0) > rows.length && (
        // Oldest first; opening one brings the next into view.
        <p className="text-muted text-xs">Showing the oldest {rows.length}.</p>
      )}
      {error && <p role="alert" className="text-sm text-danger">{error}</p>}
      <DataTable columns={columns} rows={rows} keyFor={(row) => row.jobId} loading={false} emptyMessage="" />
    </section>
  );
}

/**
 * The trust-review queue -- statements the pipeline held back not because parsing failed, but
 * because the extraction's own evidence contradicted it: a printed-versus-parsed count mismatch,
 * a row dropped where the document's own layout said one should exist, or a statement period that
 * does not hold together. `HeldImports.tsx` is the same shape of tool for the OTHER kind of hold
 * (a parser gap); this one is for a parse that worked but whose numbers do not add up.
 *
 * Every filter is optional and applies within the open queue -- or, under "Resolved", within the
 * decided holds (imported or rejected), most recently decided first. That second view is where a
 * rejected hold is found again to reopen it; without it, its URL was the only way back.
 */
function HeldStatementsContent() {
  const [view, setView] = useState<View>('open');
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<HeldStatementStatus | ''>('');
  const [bank, setBank] = useState('');
  const [bankInput, setBankInput] = useState('');
  const [engineerId, setEngineerId] = useState('');
  const [engineerIdInput, setEngineerIdInput] = useState('');
  const [olderThanHours, setOlderThanHours] = useState('');

  const list = useQuery({
    queryKey: ['held-statements-list', view, page, status, bank, engineerId, olderThanHours],
    queryFn: () => adminHeldStatementApi.list({
      resolved: view === 'resolved' || undefined,
      page,
      size: PAGE_SIZE,
      status: status || undefined,
      bank: bank || undefined,
      engineerId: engineerId || undefined,
      olderThanHours: olderThanHours ? Number(olderThanHours) : undefined,
    }),
  });

  function applyFilters() {
    setBank(bankInput.trim());
    setEngineerId(engineerIdInput.trim());
    setPage(0);
  }

  const columns: DataTableColumn<HeldStatementRow>[] = [
    {
      header: 'Held ID',
      // The reference an operator quotes -- never the raw UUID. Links straight into the detail
      // page, same as the queue's whole purpose: this row is a worklist entry, not an end state.
      render: (row) => (
        <Link to={`/held-statements/${row.heldId}`} className="font-mono text-xs text-primary hover:underline">
          {row.heldId}
        </Link>
      ),
    },
    {
      header: 'User',
      // A bare id, deliberately: this controller never joins to a user's contact details, so no
      // email or phone number can reach this screen even indirectly.
      render: (row) => <span className="text-muted text-xs font-mono">{row.userId}</span>,
    },
    {
      header: 'Bank',
      render: (row) => <span className="text-ink">{row.bankName ?? '—'}</span>,
    },
    { header: 'Created At', render: (row) => formatWhen(row.createdAt) },
    {
      header: 'Reliability',
      render: (row) => <span className="text-ink text-xs">{row.reliabilityStatus ?? '—'}</span>,
    },
    {
      header: 'Trigger',
      // The sentence, not the numbers -- the numbers are what the detail view is for.
      render: (row) => (
        <span className="text-muted text-xs" title={row.triggerSummary ?? undefined}>
          {row.triggerSummary
            ? row.triggerSummary.length > 60
              ? `${row.triggerSummary.slice(0, 60)}…`
              : row.triggerSummary
            : '—'}
        </span>
      ),
    },
    {
      header: 'Parser',
      render: (row) => <span className="text-muted text-xs font-mono">{row.parserVersion ?? '—'}</span>,
    },
    {
      header: 'Status',
      render: (row) => <span className="text-ink text-xs">{row.status.replace(/_/g, ' ')}</span>,
    },
    ...(view === 'resolved'
      ? [{ header: 'Decided', render: (row: HeldStatementRow) => (row.resolvedAt ? formatWhen(row.resolvedAt) : '—') }]
      : []),
    {
      header: 'Assigned',
      render: (row) => <span className="text-muted text-xs font-mono">{row.assignedEngineerId ?? '—'}</span>,
    },
  ];

  return (
    <div className="space-y-6">
      <p className="text-muted text-sm">
        Statements the pipeline held back because its own evidence contradicted the extraction --
        a count that does not match, a row the layout says should exist but was dropped, or a
        period that does not hold together. Nothing here is shown to the user beyond &quot;we&apos;re
        double-checking this statement by hand&quot; and a promise to hear back within 48 hours.
      </p>

      {view === 'open' && <HoldsWithoutReviewRecord />}

      <div role="tablist" aria-label="Which holds" className="inline-flex rounded-lg border border-border p-0.5 text-sm">
        {(['open', 'resolved'] as View[]).map((v) => (
          <button
            key={v}
            type="button"
            role="tab"
            aria-selected={view === v}
            onClick={() => { setView(v); setStatus(''); setPage(0); }}
            className={`rounded-md px-3 py-1.5 ${view === v ? 'bg-primary text-on-primary' : 'text-muted hover:text-ink'}`}
          >
            {v === 'open' ? 'Open' : 'Resolved'}
          </button>
        ))}
      </div>

      <FilterBar
        fields={[
          {
            type: 'select', key: 'status', value: status,
            onChange: (v) => { setStatus(v as HeldStatementStatus | ''); setPage(0); },
            placeholder: view === 'open' ? 'All open statuses' : 'All resolved statuses',
            label: 'Filter by status',
            options: view === 'open' ? STATUS_OPTIONS : RESOLVED_STATUS_OPTIONS,
          },
          {
            type: 'search', key: 'bank', value: bankInput, onChange: setBankInput,
            placeholder: 'Bank name…',
          },
          {
            type: 'search', key: 'engineerId', value: engineerIdInput, onChange: setEngineerIdInput,
            placeholder: 'Assigned engineer id…',
          },
        ]}
        onApply={applyFilters}
        applyLabel="Filter"
        trailingActions={
          <label className="flex items-center gap-1.5 text-xs text-muted whitespace-nowrap">
            Older than
            <input
              type="number"
              min={0}
              value={olderThanHours}
              onChange={(e) => { setOlderThanHours(e.target.value); setPage(0); }}
              placeholder="hours"
              className="w-20 bg-card border border-border rounded-lg px-2 py-1.5 text-sm shadow-card"
            />
            hours
          </label>
        }
      />

      <DataTable
        columns={columns}
        rows={list.data?.content ?? []}
        keyFor={(row) => row.id}
        loading={list.isLoading}
        emptyMessage="Nothing is held for trust review. Every import either completed, failed for a recognised reason, or is waiting for a parser fix elsewhere."
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

export default function HeldStatements() {
  return (
    <AdminLayout
      title="Held Statements"
      subtitle="Statements held for trust review -- extraction evidence that contradicted itself."
    >
      <RequirePermission permission="TRUST_REVIEW_MANAGE">
        <HeldStatementsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
