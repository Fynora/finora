import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Activity, LogIn, UserPlus, Wallet, ArrowLeftRight, Store,
  ListFilter, FileStack, Link2, ShieldCheck, ChevronDown, ChevronRight, X,
} from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { FilterBar } from '../components/FilterBar';
import { Pagination } from '../components/Pagination';
import { useSavedViews } from '../hooks/useSavedViews';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminAuditApi } from '../api/endpoints';
import { formatIst } from '../lib/istTime';
import { loadErrorMessage } from '../lib/loadError';
import type { AdminAuditLogRow } from '../types';

const PAGE_SIZE = 25;

/** Maps an audit action's prefix to a representative icon, first match wins. Generic Activity is
 *  the fallback -- a brand-new action string AuditService starts writing tomorrow (see
 *  ActivityController's class comment for the current full list of callers) still renders
 *  sensibly with zero changes needed here, same "registry with a safe fallback" discipline the
 *  health provider registry and GlobalSearch's TYPE_META use. */
const ACTION_ICON_RULES: Array<[string, typeof Activity]> = [
  ['USER_LOGIN', LogIn],
  ['USER_', UserPlus],
  ['ACCOUNT_', Wallet],
  ['TRANSACTION_', ArrowLeftRight],
  ['MERCHANT_', Store],
  ['RULE_', ListFilter],
  ['STATEMENT_', FileStack],
  ['RELATIONSHIP_', Link2],
  ['ROLE_', ShieldCheck],
  ['PERMISSION_', ShieldCheck],
];

function iconForAction(action: string) {
  const match = ACTION_ICON_RULES.find(([prefix]) => action.startsWith(prefix));
  return match ? match[1] : Activity;
}

function relativeTime(iso: string) {
  const date = new Date(iso);
  const seconds = Math.max(0, Math.floor((Date.now() - date.getTime()) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return formatIst(iso);
}

/** The IST calendar day of an instant, as YYYY-MM-DD. Day headings use IST because the date
 *  filter does (the backend reads dateFrom/dateTo as reporting-zone days), so an entry always sits
 *  under the day you would filter on to find it. */
function istDay(date: Date): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(date);
}

function dayLabel(iso: string) {
  const day = istDay(new Date(iso));
  const now = new Date();
  if (day === istDay(now)) return 'Today';
  if (day === istDay(new Date(now.getTime() - 86_400_000))) return 'Yesterday';
  return new Date(iso).toLocaleDateString('en-IN', {
    timeZone: 'Asia/Kolkata', weekday: 'long', month: 'long', day: 'numeric', year: 'numeric',
  });
}

/** Groups an already-sorted flat list into [dayLabel, entries[]] buckets in one pass, preserving
 *  order -- the backend sorts by createdAt, this only adds the day boundaries a timeline needs. */
function groupByDay(logs: AdminAuditLogRow[]): Array<[string, AdminAuditLogRow[]]> {
  const groups: Array<[string, AdminAuditLogRow[]]> = [];
  for (const log of logs) {
    const label = dayLabel(log.createdAt);
    const current = groups[groups.length - 1];
    if (current && current[0] === label) {
      current[1].push(log);
    } else {
      groups.push([label, [log]]);
    }
  }
  return groups;
}

/** "Name (email)", whichever parts are known, else null. */
function personLabel(name: string | null, email: string | null): string | null {
  if (name && email) return `${name} (${email})`;
  return name ?? email ?? null;
}

/** One metadata value as text. Nested objects and lists are shown as compact JSON rather than
 *  "[object Object]". */
function metadataValue(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/** The four drill-down filters, by the field the backend matches exactly. */
type DrillKey = 'userId' | 'actorId' | 'action' | 'requestId';

const DRILL_NAMES: Record<DrillKey, string> = {
  userId: 'Account',
  actorId: 'Done by',
  action: 'Action',
  requestId: 'Request',
};

function DrillButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="text-xs font-medium text-primary hover:underline whitespace-nowrap">
      {label}
    </button>
  );
}

function Detail({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-[8rem_1fr] gap-x-4 gap-y-0.5">
      <dt className="text-xs font-semibold text-muted">{term}</dt>
      <dd className="text-sm text-ink min-w-0 break-words">{children}</dd>
    </div>
  );
}

function TimelineEntry({
  log, expanded, onToggle, onDrill, canViewUsers,
}: {
  log: AdminAuditLogRow;
  expanded: boolean;
  onToggle: () => void;
  onDrill: (key: DrillKey, value: string, label: string) => void;
  canViewUsers: boolean;
}) {
  const Icon = iconForAction(log.action);
  const subject = personLabel(log.userName, log.userEmail);
  const actor = personLabel(log.actorName, log.actorEmail);
  const detailsId = `audit-entry-${log.id}`;
  // Whichever key held the acting admin (actorId, suspendedBy, ...) and a job's actor label are
  // shown as "Done by" above, so they are not repeated in the details list.
  const details = Object.entries(log.metadata ?? {}).filter(([key, value]) =>
    !(log.actorId && value === log.actorId) && !(log.systemActor && key === 'actor'));
  const doneBy = actor ?? (log.systemActor ? `automatic job ${log.systemActor}` : null);

  return (
    <div>
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        aria-controls={detailsId}
        className="w-full flex items-start gap-3 px-4 py-3 text-left hover:bg-bg"
      >
        <span className="w-8 h-8 rounded-lg bg-primary-light flex items-center justify-center flex-shrink-0 text-primary">
          <Icon size={15} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-medium text-ink">{log.action}</span>
          <span className="block text-xs text-muted mt-0.5 truncate">
            {log.entityType ?? 'No entity'}
            {doneBy && ` · by ${doneBy}`}
            {` · ${subject ?? (log.userId ? `account ${log.userId.slice(0, 8)}` : 'no account')}`}
          </span>
        </span>
        <span className="text-xs text-muted flex-shrink-0 whitespace-nowrap" title={formatIst(log.createdAt)}>
          {relativeTime(log.createdAt)}
        </span>
        {expanded
          ? <ChevronDown size={15} className="text-muted flex-shrink-0 mt-0.5" aria-hidden="true" />
          : <ChevronRight size={15} className="text-muted flex-shrink-0 mt-0.5" aria-hidden="true" />}
      </button>

      {expanded && (
        <dl id={detailsId} className="mx-4 mb-3 ml-[3.75rem] rounded-lg border border-border bg-bg p-4 space-y-3">
          <Detail term="When">{formatIst(log.createdAt)}</Detail>

          <Detail term="Account">
            {log.userId ? (
              <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span>
                  {subject ?? (canViewUsers ? 'Account no longer exists' : 'Customer (needs USER_VIEW to see who)')}
                </span>
                <span className="font-mono text-xs text-muted">{log.userId}</span>
                {canViewUsers && (
                  <Link to={`/users/${log.userId}`} className="text-xs font-medium text-primary hover:underline">
                    Open profile
                  </Link>
                )}
                <DrillButton label="Only this account" onClick={() => onDrill('userId', log.userId!, subject ?? log.userId!.slice(0, 8))} />
              </span>
            ) : '—'}
          </Detail>

          <Detail term="Done by">
            {log.actorId ? (
              <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span>{actor ?? 'Admin account no longer exists'}</span>
                <span className="font-mono text-xs text-muted">{log.actorId}</span>
                <DrillButton label="Only this admin" onClick={() => onDrill('actorId', log.actorId!, actor ?? log.actorId!.slice(0, 8))} />
              </span>
            ) : log.systemActor ? (
              <span>Automatic job <span className="font-mono text-xs">{log.systemActor}</span></span>
            ) : (
              'The account itself, or no admin was recorded'
            )}
          </Detail>

          <Detail term="Action">
            <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className="font-mono text-xs">{log.action}</span>
              <DrillButton label="Only this action" onClick={() => onDrill('action', log.action, log.action)} />
            </span>
          </Detail>

          <Detail term="On">
            {log.entityType ?? '—'}
            {log.entityId && <span className="ml-2 font-mono text-xs text-muted">{log.entityId}</span>}
          </Detail>

          <Detail term="Request">
            {log.requestId ? (
              <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="font-mono text-xs">{log.requestId}</span>
                <DrillButton label="Everything from this request" onClick={() => onDrill('requestId', log.requestId!, log.requestId!.slice(0, 8))} />
              </span>
            ) : '—'}
          </Detail>

          <Detail term="Details">
            {log.redactedAt ? (
              <span className="text-muted">
                Removed on {formatIst(log.redactedAt)} after the retention period. The entry itself is kept.
              </span>
            ) : details.length === 0 ? (
              <span className="text-muted">No details recorded.</span>
            ) : (
              <table className="text-xs">
                <tbody>
                  {details.map(([key, value]) => (
                    <tr key={key} className="align-top">
                      <th scope="row" className="pr-6 py-0.5 text-left font-medium text-muted whitespace-nowrap align-top">{key}</th>
                      <td className="py-0.5 font-mono break-all">{metadataValue(value)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Detail>
        </dl>
      )}
    </div>
  );
}

// The explicit index signature (not just named string properties) is required for this to
// satisfy useSavedViews<T>/FilterBar<T>'s `T extends Record<string, string>` constraint --
// TypeScript doesn't consider a plain interface with all-string named properties structurally
// assignable to an index-signature type without one.
interface AuditFilterValues {
  [key: string]: string;
  q: string;
  dateFrom: string;
  dateTo: string;
  sortDir: string;
  userId: string;
  actorId: string;
  action: string;
  requestId: string;
}

const BLANK_FILTERS: AuditFilterValues = {
  q: '', dateFrom: '', dateTo: '', sortDir: 'desc', userId: '', actorId: '', action: '', requestId: '',
};

const DRILL_KEYS: DrillKey[] = ['userId', 'actorId', 'action', 'requestId'];

/**
 * Admin Portal Phase 3 (Unified Activity Feed) -- a chronological timeline grouped by day, with a
 * representative icon per action.
 *
 * Phase 5 (Shared Filtering Framework) -- FilterBar (search + date range + sort) wired to the
 * backend's real q/dateFrom/dateTo/sortDir params, plus saved views.
 *
 * Drill-down (2026-11-11) -- each entry opens to show who it was about, which admin did it, what
 * it acted on, the request it came from and its recorded details, all of which the API already
 * returned but the page never showed. From there an admin can narrow the feed to that account,
 * that admin, that action or that request; each narrowing shows as a removable chip and is kept in
 * saved views.
 */
function AuditLogContent() {
  const { hasPermission } = useAdminAuth();
  const canViewUsers = hasPermission('USER_VIEW');
  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState<AuditFilterValues>(BLANK_FILTERS);
  const [searchInput, setSearchInput] = useState('');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  // What each active drill-down chip says. A saved view keeps only the ids, so a chip restored
  // from one falls back to a shortened id.
  const [drillLabels, setDrillLabels] = useState<Partial<Record<DrillKey, string>>>({});
  const savedViews = useSavedViews<AuditFilterValues>('finora-admin-views-audit-log');

  const { data, isLoading, error: loadError } = useQuery({
    queryKey: ['admin-audit-global', page, filters],
    queryFn: () => adminAuditApi.global(page, PAGE_SIZE, {
      q: filters.q || undefined,
      dateFrom: filters.dateFrom || undefined,
      dateTo: filters.dateTo || undefined,
      sortDir: filters.sortDir === 'asc' ? 'asc' : 'desc',
      userId: filters.userId || undefined,
      actorId: filters.actorId || undefined,
      action: filters.action || undefined,
      requestId: filters.requestId || undefined,
    }),
  });

  function applySearch() {
    setFilters((f) => ({ ...f, q: searchInput.trim() }));
    setPage(0);
  }

  function updateFilter<K extends keyof AuditFilterValues>(key: K, value: AuditFilterValues[K]) {
    setFilters((f) => ({ ...f, [key]: value }));
    setPage(0);
    setExpandedId(null);
  }

  function drill(key: DrillKey, value: string, label: string) {
    setDrillLabels((l) => ({ ...l, [key]: label }));
    updateFilter(key, value);
  }

  function clearDrill(key: DrillKey) {
    setDrillLabels((l) => ({ ...l, [key]: undefined }));
    updateFilter(key, '');
  }

  function applyView(values: AuditFilterValues) {
    // Views saved before drill-down existed have no drill keys; treat them as cleared.
    setFilters({ ...BLANK_FILTERS, ...values });
    setSearchInput(values.q ?? '');
    setDrillLabels({});
    setPage(0);
    setExpandedId(null);
  }

  const totalPages = data?.totalPages ?? 0;
  const groups = data ? groupByDay(data.content) : [];
  const activeDrills = DRILL_KEYS.filter((key) => filters[key]);
  const anyFilter = Boolean(filters.q || filters.dateFrom || filters.dateTo || activeDrills.length > 0);

  return (
    <div>
      <FilterBar<AuditFilterValues>
        fields={[
          { type: 'search', key: 'q', value: searchInput, onChange: setSearchInput, placeholder: 'Search action or entity type…' },
          { type: 'date', key: 'dateFrom', label: 'From', value: filters.dateFrom, onChange: (v) => updateFilter('dateFrom', v) },
          { type: 'date', key: 'dateTo', label: 'To', value: filters.dateTo, onChange: (v) => updateFilter('dateTo', v) },
          {
            type: 'select', key: 'sortDir', value: filters.sortDir, onChange: (v) => updateFilter('sortDir', v),
            label: 'Sort by date',
            options: [{ label: 'Newest first', value: 'desc' }, { label: 'Oldest first', value: 'asc' }],
          },
        ]}
        onApply={applySearch}
        savedViews={{
          views: savedViews.views,
          currentValues: { ...filters, q: searchInput.trim() },
          onApply: applyView,
          onSave: savedViews.save,
          onDelete: savedViews.remove,
        }}
      />

      {activeDrills.length > 0 && (
        <ul aria-label="Narrowed to" className="flex flex-wrap gap-2 mb-4">
          {activeDrills.map((key) => (
            <li key={key} className="inline-flex items-center gap-1.5 bg-primary-light text-primary rounded-full pl-3 pr-1 py-1 text-xs">
              <span>
                <span className="font-semibold">{DRILL_NAMES[key]}:</span>{' '}
                {drillLabels[key] ?? filters[key].slice(0, 8)}
              </span>
              <button
                type="button"
                onClick={() => clearDrill(key)}
                aria-label={`Remove the ${DRILL_NAMES[key].toLowerCase()} filter`}
                className="w-5 h-5 rounded-full flex items-center justify-center hover:bg-primary hover:text-on-primary"
              >
                <X size={12} />
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="bg-card border border-border rounded-xl2 shadow-card overflow-hidden">
        {isLoading && <p className="px-4 py-8 text-center text-muted text-sm">Loading…</p>}
        {!isLoading && loadError != null && (
          <p role="alert" className="px-4 py-4 text-center text-danger text-sm">{loadErrorMessage(loadError)}</p>
        )}
        {!isLoading && loadError == null && groups.length === 0 && (
          <p className="px-4 py-8 text-center text-muted text-sm">
            {anyFilter ? 'No activity matches these filters.' : 'No activity recorded yet.'}
          </p>
        )}
        {groups.map(([label, entries]) => (
          <div key={label}>
            <p className="px-4 py-2 text-[10px] font-bold uppercase tracking-wide text-muted bg-bg border-b border-border">
              {label}
            </p>
            <div className="divide-y divide-border">
              {entries.map((log) => (
                <TimelineEntry
                  key={log.id}
                  log={log}
                  expanded={expandedId === log.id}
                  onToggle={() => setExpandedId((id) => (id === log.id ? null : log.id))}
                  onDrill={drill}
                  canViewUsers={canViewUsers}
                />
              ))}
            </div>
          </div>
        ))}
      </div>

      {data && data.totalElements > 0 && (
        <Pagination
          page={page}
          totalPages={totalPages}
          totalElements={data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={(p) => { setPage(p); setExpandedId(null); }}
        />
      )}
    </div>
  );
}

export default function AuditLog() {
  return (
    <AdminLayout title="Audit Log" subtitle="Platform-wide activity feed, most recent first">
      <RequirePermission permission="AUDIT_VIEW">
        <AuditLogContent />
      </RequirePermission>
    </AdminLayout>
  );
}
