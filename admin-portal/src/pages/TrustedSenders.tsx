import { useId, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MailCheck, Pencil, Plus, Power, PowerOff, Search } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { FormPanel } from '../components/FormPanel';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { useNotify } from '../context/NotificationContext';
import { adminTrustedSendersApi } from '../api/endpoints';
import type { TrustedSenderDto } from '../types';

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

/** Mirrors TrustedSenderDomain.normalize: what the server will store and match, so the
 *  confirmation shows the exact domain that is about to become trusted. */
function normalizeDomain(raw: string) {
  return raw.trim().toLowerCase().replace(/\.+$/, '');
}

/** The cheap half of TrustedSenderDomain.requireValid -- the shapes an admin is most likely to
 *  paste by mistake (a URL, an address, a wildcard). The server still validates everything and is
 *  the authority; this only avoids a round trip for the obvious ones. */
function obviousDomainProblem(domain: string): string | null {
  if (!domain) return 'A domain is required.';
  if (/[\s*/@]/.test(domain)) {
    return 'Enter a bare domain such as swiggy.in -- no https://, no @, no wildcard. Matching is exact.';
  }
  return null;
}

type Pending =
  | { kind: 'add'; domain: string; merchantName: string }
  | { kind: 'disable'; sender: TrustedSenderDto }
  | { kind: 'enable'; sender: TrustedSenderDto };

function AddForm({ onCancel, onSubmit, submitting, error }: {
  onCancel: () => void;
  onSubmit: (values: { domain: string; merchantName: string }) => void;
  submitting: boolean;
  error: string | null;
}) {
  const id = useId();
  const [domain, setDomain] = useState('');
  const [merchantName, setMerchantName] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);

  return (
    <FormPanel
      title="Trust a new sender domain"
      onCancel={onCancel}
      onSubmit={(e) => {
        e.preventDefault();
        const normalized = normalizeDomain(domain);
        const problem = obviousDomainProblem(normalized);
        if (problem) {
          setLocalError(problem);
          return;
        }
        setLocalError(null);
        onSubmit({ domain: normalized, merchantName: merchantName.trim() });
      }}
      error={localError ?? error}
      submitting={submitting}
      submitLabel="Review and add"
    >
      <div className="grid gap-3 md:grid-cols-2">
        <div>
          <label htmlFor={`${id}-domain`} className="text-xs font-medium text-muted mb-1 block">Sender domain</label>
          <input
            id={`${id}-domain`}
            required
            placeholder="e.g. swiggy.in"
            value={domain}
            onChange={(e) => setDomain(e.target.value)}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm font-mono"
          />
          <p className="text-[11px] text-muted mt-1">
            Use the domain Gmail authenticates the mail as -- in a real email's original headers, the
            <code> header.from=</code> value on the <code>dmarc=pass</code> result. Exact match only:
            a subdomain needs its own entry. Cannot be changed after adding.
          </p>
        </div>
        <div>
          <label htmlFor={`${id}-name`} className="text-xs font-medium text-muted mb-1 block">Merchant name</label>
          <input
            id={`${id}-name`}
            required
            placeholder="e.g. Swiggy"
            value={merchantName}
            onChange={(e) => setMerchantName(e.target.value)}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
          <p className="text-[11px] text-muted mt-1">Display and grouping only; never used for matching.</p>
        </div>
      </div>
    </FormPanel>
  );
}

function RelabelForm({ sender, onCancel, onSubmit, submitting, error }: {
  sender: TrustedSenderDto;
  onCancel: () => void;
  onSubmit: (merchantName: string) => void;
  submitting: boolean;
  error: string | null;
}) {
  const id = useId();
  const [merchantName, setMerchantName] = useState(sender.merchantName);

  return (
    <FormPanel
      title={`Rename ${sender.domain}`}
      onCancel={onCancel}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(merchantName.trim());
      }}
      error={error}
      submitting={submitting}
      submitLabel="Save name"
    >
      <div>
        <label htmlFor={`${id}-name`} className="text-xs font-medium text-muted mb-1 block">Merchant name</label>
        <input
          id={`${id}-name`}
          required
          value={merchantName}
          onChange={(e) => setMerchantName(e.target.value)}
          className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
        />
        <p className="text-[11px] text-muted mt-1">
          The domain itself cannot be edited: that would move trust to a different sender under a row
          whose history describes the original. To trust another domain, add it; to stop trusting one,
          disable it.
        </p>
      </div>
    </FormPanel>
  );
}

function TrustedSendersContent() {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const [showAdd, setShowAdd] = useState(false);
  const [relabeling, setRelabeling] = useState<TrustedSenderDto | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [search, setSearch] = useState('');

  const { data: senders, isLoading, isError } = useQuery({
    queryKey: ['admin-trusted-senders'],
    queryFn: () => adminTrustedSendersApi.list(),
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['admin-trusted-senders'] });
  }

  const addMutation = useMutation({
    mutationFn: (values: { domain: string; merchantName: string }) => adminTrustedSendersApi.add(values),
    onSuccess: (created) => {
      setPending(null);
      setShowAdd(false);
      setFormError(null);
      invalidate();
      notify.success(`${created.domain} is now trusted.`);
    },
    onError: (err: any) => {
      // Back to the form with the server's reason -- typically "already in the registry
      // (DISABLED)", which tells the admin to enable the existing row instead.
      const msg = errorMessage(err, 'Failed to add this domain.');
      setPending(null);
      setFormError(msg);
      notify.error(msg);
    },
  });
  const relabelMutation = useMutation({
    mutationFn: ({ id, merchantName }: { id: string; merchantName: string }) =>
      adminTrustedSendersApi.relabel(id, merchantName),
    onSuccess: () => {
      setRelabeling(null);
      setFormError(null);
      invalidate();
      notify.success('Name updated.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to update the name.');
      setFormError(msg);
      notify.error(msg);
    },
  });
  const statusMutation = useMutation({
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) =>
      enable ? adminTrustedSendersApi.enable(id) : adminTrustedSendersApi.disable(id),
    onSuccess: (updated) => {
      setPending(null);
      invalidate();
      notify.success(updated.status === 'ACTIVE' ? `${updated.domain} is trusted again.` : `${updated.domain} is no longer trusted.`);
    },
    onError: (err: any) => {
      setPending(null);
      notify.error(errorMessage(err, 'Failed to change this domain.'));
    },
  });

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return senders ?? [];
    return (senders ?? []).filter(
      (s) => s.domain.toLowerCase().includes(q) || s.merchantName.toLowerCase().includes(q),
    );
  }, [senders, search]);

  const activeCount = (senders ?? []).filter((s) => s.status === 'ACTIVE').length;
  const disabledCount = (senders ?? []).length - activeCount;

  const columns: DataTableColumn<TrustedSenderDto>[] = [
    {
      header: 'Merchant',
      render: (s) => (
        <div className="flex items-center gap-2">
          <MailCheck size={13} className="text-primary flex-shrink-0" />
          <div>
            <span className="text-ink">{s.merchantName}</span>
            <span className="text-muted font-mono"> -- {s.domain}</span>
          </div>
        </div>
      ),
    },
    {
      header: 'Status',
      render: (s) => (
        <span className={`text-xs font-semibold rounded-full px-2.5 py-1 ${
          s.status === 'ACTIVE' ? 'bg-success-bg text-success' : 'bg-bg text-muted border border-border'
        }`}>
          {s.status === 'ACTIVE' ? 'Trusted' : 'Disabled'}
        </span>
      ),
    },
    {
      header: 'Added',
      render: (s) => <span className="text-xs text-muted">{new Date(s.createdAt).toLocaleDateString()}</span>,
    },
    {
      header: 'Actions',
      headerClassName: 'text-right',
      cellClassName: 'text-right',
      render: (s) => (
        <div className="inline-flex items-center gap-1">
          <button
            type="button"
            title="Rename"
            aria-label={`Rename ${s.domain}`}
            onClick={() => {
              setRelabeling(s);
              setShowAdd(false);
              setFormError(null);
            }}
            className="w-8 h-8 rounded-lg hover:bg-bg text-muted hover:text-ink inline-flex items-center justify-center"
          >
            <Pencil size={14} />
          </button>
          {s.status === 'ACTIVE' ? (
            <button
              type="button"
              title="Stop trusting this domain"
              aria-label={`Disable ${s.domain}`}
              onClick={() => setPending({ kind: 'disable', sender: s })}
              className="w-8 h-8 rounded-lg hover:bg-bg text-muted hover:text-danger inline-flex items-center justify-center"
            >
              <PowerOff size={14} />
            </button>
          ) : (
            <button
              type="button"
              title="Trust this domain again"
              aria-label={`Enable ${s.domain}`}
              onClick={() => setPending({ kind: 'enable', sender: s })}
              className="w-8 h-8 rounded-lg hover:bg-bg text-muted hover:text-ink inline-flex items-center justify-center"
            >
              <Power size={14} />
            </button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <p className="text-sm text-muted max-w-2xl">
          The sender domains Fynora will read receipts from. Gmail sync parses a message only when
          Gmail itself authenticated it as one of these exact domains. Adding a domain is a security
          decision: authenticated mail from it can become transactions in users' ledgers once a
          template or parser exists for it. Every change is audited, and nothing is ever deleted --
          disabling is the delete.
        </p>
        {!showAdd && (
          <button
            type="button"
            onClick={() => {
              setShowAdd(true);
              setRelabeling(null);
              setFormError(null);
            }}
            className="inline-flex items-center gap-1.5 bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2.5 flex-shrink-0"
          >
            <Plus size={15} /> Trust a domain
          </button>
        )}
      </div>

      {showAdd && (
        <AddForm
          submitting={addMutation.isPending}
          error={formError}
          onCancel={() => {
            setShowAdd(false);
            setFormError(null);
          }}
          onSubmit={(values) => {
            setFormError(null);
            setPending({ kind: 'add', ...values });
          }}
        />
      )}

      {relabeling && (
        <RelabelForm
          key={relabeling.id}
          sender={relabeling}
          submitting={relabelMutation.isPending}
          error={formError}
          onCancel={() => {
            setRelabeling(null);
            setFormError(null);
          }}
          onSubmit={(merchantName) => relabelMutation.mutate({ id: relabeling.id, merchantName })}
        />
      )}

      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="search"
            aria-label="Search trusted senders"
            placeholder="Search domain or merchant"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="bg-card border border-border rounded-lg pl-8 pr-3 py-2 text-sm w-72"
          />
        </div>
        {senders && (
          <span className="text-xs text-muted">
            {activeCount} trusted, {disabledCount} disabled
          </span>
        )}
      </div>

      {isError && (
        <p className="text-sm text-danger bg-danger-bg rounded-lg px-3 py-2">Could not load the trusted senders.</p>
      )}

      <DataTable
        columns={columns}
        rows={filtered}
        keyFor={(s) => s.id}
        loading={isLoading}
        emptyMessage={search.trim() ? 'No trusted sender matches that search.' : 'No trusted senders yet.'}
      />

      {pending?.kind === 'add' && (
        <ConfirmDialog
          title={`Trust ${pending.domain}?`}
          message={`Mail Gmail authenticates as exactly ${pending.domain} (not its subdomains) will be examined for receipts, `
            + `and once a template or parser exists it can become transactions in users' ledgers. `
            + `Only add a domain you have seen a real receipt from. Only mail that arrives from now on is examined: `
            + `mail from this domain that Fynora already scanned and skipped is not read again.`}
          confirmLabel="Trust this domain"
          busy={addMutation.isPending}
          onConfirm={() => addMutation.mutate({ domain: pending.domain, merchantName: pending.merchantName })}
          onCancel={() => setPending(null)}
        />
      )}
      {pending?.kind === 'disable' && (
        <ConfirmDialog
          title={`Stop trusting ${pending.sender.domain}?`}
          message="New mail from this domain will no longer be examined. Mail that arrives while it is disabled is skipped for good: trusting it again does not go back for it. The row is kept."
          confirmLabel="Stop trusting"
          danger
          busy={statusMutation.isPending}
          onConfirm={() => statusMutation.mutate({ id: pending.sender.id, enable: false })}
          onCancel={() => setPending(null)}
        />
      )}
      {pending?.kind === 'enable' && (
        <ConfirmDialog
          title={`Trust ${pending.sender.domain} again?`}
          message="Mail Gmail authenticates as this exact domain will be examined for receipts again, from now on. Mail skipped while it was disabled is not read again."
          confirmLabel="Trust again"
          busy={statusMutation.isPending}
          onConfirm={() => statusMutation.mutate({ id: pending.sender.id, enable: true })}
          onCancel={() => setPending(null)}
        />
      )}
    </div>
  );
}

export default function TrustedSenders() {
  return (
    <AdminLayout
      title="Trusted Senders"
      subtitle="Which authenticated sender domains Gmail receipt sync may read -- no engineering release needed"
    >
      <RequirePermission permission="SYSTEM_SETTINGS">
        <TrustedSendersContent />
      </RequirePermission>
    </AdminLayout>
  );
}
