import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, Paperclip } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { adminSupportTicketApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';
import { useNotify } from '../context/NotificationContext';
import { formatWhen } from '../lib/formatWhen';
import type { ClientPlatform, SupportTicketCategory, SupportTicketStatus } from '../types';

const CATEGORY_LABELS: Record<SupportTicketCategory, string> = {
  STATEMENT_IMPORT: 'Statement import',
  CATEGORIZATION: 'Categorization',
  ACCOUNT_LINKING: 'Account linking',
  DATA_ACCURACY: 'Data accuracy',
  TECHNICAL_ISSUE: 'Technical issue',
  SUSPENSION_APPEAL: 'Suspension appeal',
  SUSPENSION_DELETION_REQUEST: 'Deletion request (suspended)',
  OTHER: 'Other',
};

/** Mirrors SupportTicket.Status.canTransitionTo on the backend -- shown here only so an admin is
 *  never offered an illegal move in the dropdown; the backend's own check is still what actually
 *  enforces it (D6: a resolved ticket cannot be reopened, D7: OPEN can close in one step). */
// Must match SupportTicket.Status.canTransitionTo on the backend. RESOLVED -> CLOSED is allowed
// there; this said RESOLVED: [] until 2026-11-12, so a resolved ticket could never be closed here.
const LEGAL_NEXT_STATUSES: Record<SupportTicketStatus, SupportTicketStatus[]> = {
  OPEN: ['IN_PROGRESS', 'RESOLVED', 'CLOSED'],
  IN_PROGRESS: ['RESOLVED', 'CLOSED'],
  RESOLVED: ['CLOSED'],
  CLOSED: [],
};

const PLATFORM_LABELS: Record<ClientPlatform, string> = {
  WEB: 'the web app',
  MOBILE_ANDROID: 'the Android app',
  MOBILE_IOS: 'the iPhone app',
};

/** Longest message the customer email can carry -- SupportTicketDto.MAX_CUSTOMER_MESSAGE_LENGTH. */
const MAX_CUSTOMER_MESSAGE = 2000;

function onActionErrorMessage(error: unknown): string {
  const response = (error as { response?: { data?: { message?: string } } })?.response;
  return response?.data?.message ?? 'That action could not be completed.';
}

function bytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

function SupportTicketDetailContent({ id }: { id: string }) {
  const queryClient = useQueryClient();
  const [actionError, setActionError] = useState<string | null>(null);
  const [noteDraft, setNoteDraft] = useState('');
  const [confirmingTakeover, setConfirmingTakeover] = useState(false);
  const [duplicateRef, setDuplicateRef] = useState('');
  const [confirmingDuplicate, setConfirmingDuplicate] = useState(false);
  // Resolving or closing asks first, and offers to email the customer with an optional note.
  const [finishing, setFinishing] = useState<'RESOLVED' | 'CLOSED' | null>(null);
  const [tellCustomer, setTellCustomer] = useState(true);
  const [customerMessage, setCustomerMessage] = useState('');
  const canViewUsers = useAdminAuth().hasPermission('USER_VIEW');
  const notify = useNotify();

  const detail = useQuery({
    queryKey: ['support-ticket-detail-admin', id],
    queryFn: () => adminSupportTicketApi.get(id),
  });
  const notes = useQuery({
    queryKey: ['support-ticket-notes', id],
    queryFn: () => adminSupportTicketApi.notes(id),
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['support-ticket-detail-admin', id] });
    void queryClient.invalidateQueries({ queryKey: ['support-tickets-list'] });
  }

  function onError(error: unknown) {
    setActionError(onActionErrorMessage(error));
  }

  const updateStatus = useMutation({
    mutationFn: ({ status, notifyCustomer, message }: { status: SupportTicketStatus; notifyCustomer: boolean; message?: string }) =>
      adminSupportTicketApi.updateStatus(id, status, { notifyCustomer, message }),
    onSuccess: (result, vars) => {
      setActionError(null);
      setFinishing(null);
      setCustomerMessage('');
      invalidate();
      if (vars.notifyCustomer && !result.customerEmailed) {
        // The status changed; only the email did not go. Say so, and why, so the admin can reach
        // the customer another way.
        setActionError(`Status changed, but the customer was not emailed. ${result.notEmailedReason ?? ''}`.trim());
      } else if (result.customerEmailed) {
        notify.success('Status changed. The customer will be emailed.');
      }
    },
    onError: (error) => { setFinishing(null); onError(error); },
  });

  function startStatusChange(next: SupportTicketStatus, current: SupportTicketStatus) {
    if (next === 'RESOLVED' || next === 'CLOSED') {
      // Closing a ticket already resolved is housekeeping: the customer was told when it was
      // resolved, so emailing them again is off unless the admin turns it on.
      setTellCustomer(!(next === 'CLOSED' && current === 'RESOLVED'));
      setCustomerMessage('');
      setFinishing(next);
    } else {
      updateStatus.mutate({ status: next, notifyCustomer: false });
    }
  }
  const markDuplicate = useMutation({
    mutationFn: (referenceTicketNumber: string) => adminSupportTicketApi.markDuplicate(id, referenceTicketNumber),
    onSuccess: () => { setActionError(null); setConfirmingDuplicate(false); setDuplicateRef(''); invalidate(); },
    // The server's own message says exactly what is wrong (a different customer, a reference that
    // is itself a duplicate and which ticket to use instead, ...), so it is shown as is.
    onError: (error) => { setConfirmingDuplicate(false); onError(error); },
  });
  const claim = useMutation({
    mutationFn: () => adminSupportTicketApi.claim(id),
    onSuccess: () => { setActionError(null); setConfirmingTakeover(false); invalidate(); },
    onError: (error) => { setConfirmingTakeover(false); onError(error); },
  });
  const unclaim = useMutation({
    mutationFn: () => adminSupportTicketApi.unclaim(id),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const addNote = useMutation({
    mutationFn: (note: string) => adminSupportTicketApi.addNote(id, note),
    onSuccess: () => {
      setActionError(null);
      setNoteDraft('');
      void queryClient.invalidateQueries({ queryKey: ['support-ticket-notes', id] });
    },
    onError,
  });
  const download = useMutation({
    mutationFn: ({ attachmentId, filename }: { attachmentId: string; filename: string }) =>
      adminSupportTicketApi.downloadAttachment(id, attachmentId, filename),
    onError,
  });

  if (detail.isLoading) {
    return <p className="text-muted text-sm">Loading…</p>;
  }
  if (detail.isError || !detail.data) {
    return <p className="text-muted text-sm">No such support ticket.</p>;
  }

  const ticket = detail.data;
  const nextStatuses = LEGAL_NEXT_STATUSES[ticket.status];

  return (
    <div className="space-y-6">
      <Link to="/support-tickets" className="text-xs text-primary hover:underline">
        &larr; Back to queue
      </Link>

      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-ink font-mono">{ticket.ticketNumber}</h2>
          <p className="text-ink text-sm mt-1">{ticket.subject}</p>
          <p className="text-muted text-xs mt-1">
            {CATEGORY_LABELS[ticket.category]} · Opened {formatWhen(ticket.createdAt)} · Sent from{' '}
            {PLATFORM_LABELS[ticket.source] ?? ticket.source}{ticket.appVersion ? ` ${ticket.appVersion}` : ''}
          </p>
          <p className="text-sm text-ink mt-2" data-testid="ticket-customer">
            {ticket.customerName || ticket.customerEmail ? (
              <>
                From{' '}
                {canViewUsers ? (
                  <Link to={`/users/${ticket.userId}`} className="font-medium text-primary hover:underline">
                    {ticket.customerName ?? ticket.customerEmail}
                  </Link>
                ) : <span className="font-medium">{ticket.customerName ?? ticket.customerEmail}</span>}
                {ticket.customerName && ticket.customerEmail && <span className="text-muted"> · {ticket.customerEmail}</span>}
              </>
            ) : (
              <span className="text-muted">
                {canViewUsers ? 'From an account that no longer exists' : 'Customer hidden: needs the USER_VIEW permission'}
              </span>
            )}
          </p>
        </div>
        <span className="text-xs font-medium text-ink rounded-lg border border-border px-2.5 py-1">
          {ticket.status.replace(/_/g, ' ')}
        </span>
      </div>

      {actionError && (
        <div className="rounded-lg bg-danger-bg p-3">
          <p className="text-sm text-danger">{actionError}</p>
        </div>
      )}

      {ticket.duplicateOfTicketId && (
        <div className="rounded-lg border border-border bg-card p-3 text-sm text-ink">
          Closed as a duplicate of{' '}
          <Link to={`/support-tickets/${ticket.duplicateOfTicketId}`} className="font-mono text-primary hover:underline">
            {ticket.duplicateOfTicketNumber}
          </Link>
          . The customer was emailed.
        </div>
      )}

      {/* Description + attachments */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-4">
        <h3 className="text-sm font-semibold text-ink">Description</h3>
        <p className="text-sm text-ink whitespace-pre-wrap">{ticket.description}</p>

        {ticket.attachments.length > 0 && (
          <div className="space-y-1.5 border-t border-border pt-3">
            {ticket.attachments.map((a) => (
              <button
                key={a.id}
                type="button"
                onClick={() => download.mutate({ attachmentId: a.id, filename: a.filename })}
                disabled={download.isPending}
                className="flex items-center gap-2 text-xs text-primary hover:underline disabled:opacity-50"
              >
                <Paperclip className="h-3.5 w-3.5" />
                {a.filename}
                <span className="text-muted">({bytes(a.sizeBytes)})</span>
                <Download className="h-3.5 w-3.5" />
              </button>
            ))}
          </div>
        )}
      </section>

      {/* Status */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
        <h3 className="text-sm font-semibold text-ink">Status</h3>
        {nextStatuses.length === 0 ? (
          <p className="text-xs text-muted">
            Closed tickets can&apos;t change status again -- the customer would file a new ticket instead.
          </p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {nextStatuses.map((next) => (
              <button
                key={next}
                type="button"
                onClick={() => startStatusChange(next, ticket.status)}
                disabled={updateStatus.isPending}
                className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
              >
                Move to {next.replace(/_/g, ' ')}
              </button>
            ))}
          </div>
        )}
      </section>

      {/* Duplicate -- only while the ticket can still be closed; a closed ticket is never re-marked. */}
      {ticket.status !== 'CLOSED' && (
        <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
          <h3 className="text-sm font-semibold text-ink">Duplicate</h3>
          <p className="text-xs text-muted">
            If this customer already raised the same issue, enter that ticket&apos;s number. This ticket is
            closed and the customer is emailed that it is a duplicate. It must be another ticket from the
            same customer.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor="duplicate-ref" className="sr-only">Number of the ticket this duplicates</label>
            <input
              id="duplicate-ref"
              value={duplicateRef}
              onChange={(e) => setDuplicateRef(e.target.value)}
              placeholder="SUP-000042"
              className="rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink font-mono"
            />
            <button
              type="button"
              onClick={() => setConfirmingDuplicate(true)}
              disabled={markDuplicate.isPending || duplicateRef.trim().length === 0}
              className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
            >
              Mark as duplicate
            </button>
          </div>
        </section>
      )}

      {/* Claim */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
        <h3 className="text-sm font-semibold text-ink">Claim</h3>
        <p className="text-xs text-muted">
          Claimed by:{' '}
          <span className="text-ink">
            {!ticket.claimedByAdminId ? '— unclaimed —'
              : ticket.claimedByYou ? 'You'
              : ticket.claimedByAdminName ?? 'An admin account that no longer exists'}
          </span>
        </p>
        <div className="flex flex-wrap gap-2">
          {!ticket.claimedByAdminId ? (
            <button
              type="button"
              onClick={() => claim.mutate()}
              disabled={claim.isPending}
              className="rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-on-primary hover:bg-primary-dark disabled:opacity-50"
            >
              Claim
            </button>
          ) : !ticket.claimedByYou && (
            <button
              type="button"
              onClick={() => setConfirmingTakeover(true)}
              disabled={claim.isPending}
              className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
            >
              Take over
            </button>
          )}
          {ticket.claimedByAdminId && (
            <button
              type="button"
              onClick={() => unclaim.mutate()}
              disabled={unclaim.isPending}
              className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
            >
              Unclaim
            </button>
          )}
        </div>
      </section>

      {/* Internal notes */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-4">
        <h3 className="text-sm font-semibold text-ink">Internal notes</h3>
        <p className="text-xs text-muted">Visible to admins only -- never shown to the customer.</p>

        <ul className="space-y-2">
          {(notes.data ?? []).map((note) => (
            <li key={note.id} className="text-xs border-l-2 border-border pl-3">
              <span className="text-ink font-medium">{note.adminName ?? 'Former admin'}</span>{' '}
              <span className="text-muted">{formatWhen(note.createdAt)}</span>
              <p className="text-ink mt-0.5 whitespace-pre-wrap">{note.note}</p>
            </li>
          ))}
          {notes.data?.length === 0 && <p className="text-muted text-xs">No internal notes yet.</p>}
        </ul>

        <div className="space-y-2 border-t border-border pt-4">
          <textarea
            value={noteDraft}
            onChange={(e) => setNoteDraft(e.target.value)}
            maxLength={10000}
            rows={2}
            placeholder="Add a note visible to other admins…"
            className="w-full rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
          />
          <button
            type="button"
            onClick={() => addNote.mutate(noteDraft.trim())}
            disabled={addNote.isPending || noteDraft.trim().length === 0}
            className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
          >
            {addNote.isPending ? 'Adding…' : 'Add note'}
          </button>
        </div>
      </section>

      {confirmingDuplicate && (
        <ConfirmDialog
          title="Close as a duplicate?"
          message={`${ticket.ticketNumber} will be closed as a duplicate of ${duplicateRef.trim().toUpperCase()} and the customer will be emailed. This can't be undone.`}
          confirmLabel="Close as duplicate"
          busy={markDuplicate.isPending}
          onConfirm={() => markDuplicate.mutate(duplicateRef.trim())}
          onCancel={() => setConfirmingDuplicate(false)}
        />
      )}

      {finishing && (
        <ConfirmDialog
          title={finishing === 'RESOLVED' ? `Resolve ${ticket.ticketNumber}?` : `Close ${ticket.ticketNumber}?`}
          message={finishing === 'RESOLVED'
            ? 'Use this when the issue is fixed or answered.'
            : 'Use this when the ticket ends without a fix: no reply, out of scope, or spam.'}
          confirmLabel={finishing === 'RESOLVED' ? 'Resolve' : 'Close'}
          busy={updateStatus.isPending}
          confirmDisabled={customerMessage.length > MAX_CUSTOMER_MESSAGE}
          onConfirm={() => updateStatus.mutate({
            status: finishing,
            notifyCustomer: tellCustomer,
            message: tellCustomer && customerMessage.trim() ? customerMessage.trim() : undefined,
          })}
          onCancel={() => setFinishing(null)}
        >
          <label className="mt-3 flex items-center gap-2 text-sm text-ink">
            <input type="checkbox" checked={tellCustomer} onChange={(e) => setTellCustomer(e.target.checked)} />
            Email the customer that it is {finishing === 'RESOLVED' ? 'resolved' : 'closed'}
          </label>
          {tellCustomer && (
            <div className="mt-3">
              <label htmlFor="customer-message" className="text-xs font-medium text-muted mb-1 block">
                Note to include in the email (optional)
              </label>
              <textarea
                id="customer-message"
                value={customerMessage}
                onChange={(e) => setCustomerMessage(e.target.value)}
                rows={4}
                className="w-full rounded-lg border border-border bg-bg px-3 py-2 text-sm text-ink"
              />
              <p className={`text-[11px] mt-1 ${customerMessage.length > MAX_CUSTOMER_MESSAGE ? 'text-danger' : 'text-muted'}`}>
                {customerMessage.length} / {MAX_CUSTOMER_MESSAGE}. The customer sees exactly this text. Only
                customers with a verified email address are emailed.
              </p>
            </div>
          )}
        </ConfirmDialog>
      )}

      {confirmingTakeover && (
        <ConfirmDialog
          title="Take over this ticket?"
          message={`This ticket is claimed by ${ticket.claimedByAdminName ?? 'another admin'}. Taking it over reassigns it to you -- they won't be notified automatically.`}
          confirmLabel="Take over"
          busy={claim.isPending}
          onConfirm={() => claim.mutate()}
          onCancel={() => setConfirmingTakeover(false)}
        />
      )}
    </div>
  );
}

export default function SupportTicketDetail() {
  const { id } = useParams<{ id: string }>();
  return (
    <AdminLayout title="Support Ticket" subtitle="Description, attachments, status, claim, and internal notes.">
      <RequirePermission permission="SUPPORT_MANAGE">
        {id ? <SupportTicketDetailContent id={id} /> : <p className="text-muted text-sm">No ticket id.</p>}
      </RequirePermission>
    </AdminLayout>
  );
}
