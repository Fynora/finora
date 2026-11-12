import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { FilterBar } from '../components/FilterBar';
import { adminSupportTicketApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';
import { formatWhen } from '../lib/formatWhen';
import type { SupportTicketCategory, SupportTicketRow, SupportTicketStatus } from '../types';

const PAGE_SIZE = 25;

const STATUS_OPTIONS: { label: string; value: SupportTicketStatus }[] = [
  { label: 'Open', value: 'OPEN' },
  { label: 'In Progress', value: 'IN_PROGRESS' },
  { label: 'Resolved', value: 'RESOLVED' },
  { label: 'Closed', value: 'CLOSED' },
];

const CATEGORY_OPTIONS: { label: string; value: SupportTicketCategory }[] = [
  { label: 'Statement import', value: 'STATEMENT_IMPORT' },
  { label: 'Categorization', value: 'CATEGORIZATION' },
  { label: 'Account linking', value: 'ACCOUNT_LINKING' },
  { label: 'Data accuracy', value: 'DATA_ACCURACY' },
  { label: 'Technical issue', value: 'TECHNICAL_ISSUE' },
  { label: 'Other', value: 'OTHER' },
  { label: 'Suspension appeal', value: 'SUSPENSION_APPEAL' },
  { label: 'Deletion request (suspended)', value: 'SUSPENSION_DELETION_REQUEST' },
];

const STATUS_TONE: Record<SupportTicketStatus, string> = {
  // Bug fix: 'text-accent' was never a defined Tailwind color in this app (no `accent` entry in
  // tailwind.config.js) -- OPEN tickets rendered with no color applied at all, silently falling
  // through to the inherited default text color. Same class of bug as the user frontend's own
  // dead `bg-surface` class before it got a real token (see index.css's history on that pattern).
  OPEN: 'text-info',
  IN_PROGRESS: 'text-warning',
  RESOLVED: 'text-success',
  CLOSED: 'text-muted',
};

/**
 * Support, Help & Feedback v1, Phase 9. Same shape as HeldStatements.tsx -- a filtered,
 * server-paginated queue with each row linking into its own detail page. Primary columns per the
 * proposal: Ticket Number, Subject, Category, Status, Created At -- Claimed by is added ahead of
 * any row being opened, because the plan itself requires it: "since takeover is permitted, this
 * column is the safeguard, so it has to be visible before anyone opens a ticket." Since
 * 2026-11-12 it names the admin ("You" for the signed-in one), a Customer column names who raised
 * the ticket, and a Mine / Unclaimed filter narrows the queue.
 */
function SupportTicketsContent() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<SupportTicketStatus | ''>('');
  const [category, setCategory] = useState<SupportTicketCategory | ''>('');
  // Staged then applied, same as HeldStatements.tsx's own search-type fields -- a keystroke
  // should not fire a new query on every character, unlike the select filters above, which apply
  // immediately.
  const [q, setQ] = useState('');
  const [qInput, setQInput] = useState('');
  // Mine / Unclaimed, applied immediately like the other selects.
  const [claim, setClaim] = useState<'MINE' | 'UNCLAIMED' | ''>('');
  const canViewUsers = useAdminAuth().hasPermission('USER_VIEW');

  const list = useQuery({
    queryKey: ['support-tickets-list', page, status, category, q, claim],
    queryFn: () => adminSupportTicketApi.list({
      page,
      size: PAGE_SIZE,
      status: status || undefined,
      category: category || undefined,
      q: q || undefined,
      claim: claim || undefined,
    }),
  });

  function applyFilters() {
    setQ(qInput.trim());
    setPage(0);
  }

  const columns: DataTableColumn<SupportTicketRow>[] = [
    {
      header: 'Ticket Number',
      render: (row) => (
        <Link to={`/support-tickets/${row.id}`} className="font-mono text-xs text-primary hover:underline">
          {row.ticketNumber}
        </Link>
      ),
    },
    {
      header: 'Subject',
      render: (row) => <span className="text-ink text-sm">{row.subject}</span>,
    },
    {
      header: 'Customer',
      // Named only for an admin who could look the customer up anyway (USER_VIEW); the server
      // sends no name otherwise.
      render: (row) => {
        const who = row.customerName ?? row.customerEmail;
        if (!who) return <span className="text-muted text-xs">{canViewUsers ? 'Deleted account' : 'Hidden'}</span>;
        return (
          <div className="min-w-0">
            {canViewUsers ? (
              <Link to={`/users/${row.userId}`} className="text-sm text-ink hover:text-primary">{row.customerName ?? row.customerEmail}</Link>
            ) : <span className="text-sm text-ink">{row.customerName ?? row.customerEmail}</span>}
            {row.customerName && row.customerEmail && <p className="text-xs text-muted truncate">{row.customerEmail}</p>}
          </div>
        );
      },
    },
    {
      header: 'Category',
      render: (row) => (
        <span className="text-muted text-xs">
          {CATEGORY_OPTIONS.find((c) => c.value === row.category)?.label ?? row.category}
        </span>
      ),
    },
    {
      header: 'Status',
      render: (row) => (
        <div>
          <span className={`text-xs font-medium ${STATUS_TONE[row.status]}`}>{row.status.replace(/_/g, ' ')}</span>
          {row.duplicateOfTicketNumber && (
            <p className="text-muted text-xs mt-0.5">Duplicate of {row.duplicateOfTicketNumber}</p>
          )}
        </div>
      ),
    },
    {
      header: 'Claimed by',
      render: (row) => {
        if (!row.claimedByAdminId) return <span className="text-muted text-xs">—</span>;
        if (row.claimedByYou) return <span className="text-xs font-semibold text-ink">You</span>;
        return <span className="text-muted text-xs">{row.claimedByAdminName ?? 'Former admin'}</span>;
      },
    },
    { header: 'Created At', render: (row) => formatWhen(row.createdAt) },
  ];

  return (
    <div className="space-y-6">
      <FilterBar
        fields={[
          {
            type: 'search', key: 'q', value: qInput, onChange: setQInput,
            placeholder: 'Search ticket number or subject…',
          },
          {
            type: 'select', key: 'status', value: status,
            onChange: (v) => { setStatus(v as SupportTicketStatus | ''); setPage(0); },
            placeholder: 'All statuses',
            label: 'Filter by status',
            options: STATUS_OPTIONS,
          },
          {
            type: 'select', key: 'category', value: category,
            onChange: (v) => { setCategory(v as SupportTicketCategory | ''); setPage(0); },
            placeholder: 'All categories',
            label: 'Filter by category',
            options: CATEGORY_OPTIONS,
          },
          {
            type: 'select', key: 'claim', value: claim,
            onChange: (v) => { setClaim(v as 'MINE' | 'UNCLAIMED' | ''); setPage(0); },
            placeholder: 'Anyone',
            label: 'Filter by who holds it',
            options: [{ label: 'Mine', value: 'MINE' }, { label: 'Unclaimed', value: 'UNCLAIMED' }],
          },
        ]}
        onApply={applyFilters}
        applyLabel="Search"
      />

      <DataTable
        columns={columns}
        rows={list.data?.content ?? []}
        keyFor={(row) => row.id}
        loading={list.isLoading}
        error={list.error}
        emptyMessage="No support tickets match this filter."
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

export default function SupportTickets() {
  return (
    <AdminLayout title="Support Tickets" subtitle="Requests users have filed, oldest first, so the longest wait is at the top.">
      <RequirePermission permission="SUPPORT_MANAGE">
        <SupportTicketsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
