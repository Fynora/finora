import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, Archive, Hourglass, FileStack } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { StatCard } from '../components/StatCard';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { LoadError } from '../components/LoadError';
import { adminImportHealthApi, type ImportFailureCount, type StatementRefreshSummary } from '../api/endpoints';
import { formatIst } from '../lib/istTime';
import { describeFailure } from './layoutStudioTerms';

const WINDOWS = [
  { days: 1, label: 'Last 24 hours' },
  { days: 7, label: 'Last 7 days' },
  { days: 30, label: 'Last 30 days' },
  { days: 90, label: 'Last 90 days' },
] as const;

/** Every reason the summary can return; a failure with no stored code comes back as this literal
 *  (StatementAnalysisReportService.failureCounts). */
const UNKNOWN_FAILURE = 'UNKNOWN_FAILURE';

function failureLabel(code: string) {
  return code === UNKNOWN_FAILURE ? 'Unclassified failure' : describeFailure(code).label;
}

function failureMeaning(code: string) {
  return code === UNKNOWN_FAILURE
    ? 'The import failed before it could be given a reason.'
    : describeFailure(code).meaning;
}

const count = (n: number) => n.toLocaleString('en-IN');

const FAILURE_COLUMNS: DataTableColumn<ImportFailureCount>[] = [
  {
    header: 'Reason',
    render: (f) => (
      <div>
        <p className="text-ink font-medium">{failureLabel(f.failureCode)}</p>
        <p className="text-xs text-muted">{failureMeaning(f.failureCode)}</p>
        <p className="text-xs text-muted font-mono mt-0.5">{f.failureCode}</p>
      </div>
    ),
  },
  { header: 'Imports', render: (f) => <span className="font-mono">{count(f.count)}</span> },
  { header: 'Last seen', render: (f) => <span className="text-xs text-muted">{formatIst(f.lastSeen)}</span> },
  {
    header: 'Most common layout',
    render: (f) => (f.bank ? <span className="text-ink">{f.bank}</span> : <span className="text-muted">Not named</span>),
  },
];

function FailuresSection() {
  const [days, setDays] = useState<number>(7);
  const failures = useQuery({
    queryKey: ['import-health-failures', days],
    // The window is measured from the moment of the fetch, so a refetch looks at the same span.
    queryFn: () => adminImportHealthApi.failures(new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString()),
  });
  const total = (failures.data ?? []).reduce((sum, f) => sum + f.count, 0);

  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-ink">Failed imports</h3>
          <p className="text-xs text-muted">
            Customers' own imports only; admin test uploads don't count. Most frequent reason first.
            {failures.data && failures.data.length > 0 && ` ${count(total)} failed in this window.`}
          </p>
        </div>
        <label className="text-xs text-muted flex items-center gap-2">
          Window
          <select
            value={days}
            onChange={(e) => setDays(Number(e.target.value))}
            className="bg-card border border-border rounded-lg px-2 py-1 text-sm text-ink"
          >
            {WINDOWS.map((w) => <option key={w.days} value={w.days}>{w.label}</option>)}
          </select>
        </label>
      </div>
      <DataTable
        columns={FAILURE_COLUMNS}
        rows={failures.data}
        keyFor={(f) => f.failureCode}
        loading={failures.isLoading}
        error={failures.error}
        emptyMessage="No customer import failed in this window."
      />
    </section>
  );
}

function TelemetrySection() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['import-health-telemetry'],
    queryFn: () => adminImportHealthApi.telemetry(),
  });

  const of = (n: number) => (data && data.withTelemetry > 0 ? `${count(n)} of ${count(data.withTelemetry)}` : count(n));

  return (
    <section className="space-y-3">
      <div>
        <h3 className="text-sm font-semibold text-ink">Import checks</h3>
        <p className="text-xs text-muted">
          What the trust checks found on completed imports. Only completed imports carry check results, and only
          those read since the checks began recording, so every count here is out of those.
        </p>
      </div>
      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      <LoadError error={error} what="the import checks" />
      {data && (
        <>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <StatCard icon={FileStack} label="Completed imports" value={count(data.completedJobs)} />
            <StatCard icon={CheckCircle2} label="With check results" value={count(data.withTelemetry)} />
            <StatCard icon={Archive} label="From before the checks" value={count(data.predatesTelemetry)} />
            <StatCard icon={Hourglass} label="Not completed" value={count(data.notCompleted)} />
          </div>

          <div className="grid gap-4 md:grid-cols-2">
            <div className="bg-card border border-border rounded-xl2 p-5">
              <h4 className="text-xs font-semibold text-muted uppercase tracking-wide mb-3">Result</h4>
              <dl className="space-y-2 text-sm">
                {Object.entries(data.byReliabilityStatus).map(([status, n]) => (
                  <div key={status} className="flex justify-between gap-4">
                    <dt className="text-ink">{reliabilityLabel(status)}</dt>
                    <dd className="font-mono">{of(n)}</dd>
                  </div>
                ))}
              </dl>
            </div>
            <div className="bg-card border border-border rounded-xl2 p-5">
              <h4 className="text-xs font-semibold text-muted uppercase tracking-wide mb-3">Findings</h4>
              <dl className="space-y-2 text-sm">
                <div className="flex justify-between gap-4">
                  <dt className="text-ink">At least one check failed</dt>
                  <dd className="font-mono">{of(data.withFailedFindings)}</dd>
                </div>
                <div className="flex justify-between gap-4">
                  <dt className="text-ink">At least one warning</dt>
                  <dd className="font-mono">{of(data.withWarningFindings)}</dd>
                </div>
                <div className="flex justify-between gap-4">
                  <dt className="text-ink">Column headings rebuilt, unsure</dt>
                  <dd className="font-mono">{of(data.headerReconstructionUncertain)}</dd>
                </div>
              </dl>
              <h4 className="text-xs font-semibold text-muted uppercase tracking-wide mt-5 mb-3">How the text was read</h4>
              {Object.keys(data.byTextSource).length === 0 ? (
                <p className="text-sm text-muted">Nothing recorded yet.</p>
              ) : (
                <dl className="space-y-2 text-sm">
                  {Object.entries(data.byTextSource).map(([source, n]) => (
                    <div key={source} className="flex justify-between gap-4">
                      <dt className="text-ink">{textSourceLabel(source)}</dt>
                      <dd className="font-mono">{of(n)}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </div>
          </div>

          <div>
            <h4 className="text-xs font-semibold text-muted uppercase tracking-wide mb-2">By parser build (latest 20)</h4>
            <DataTable
              columns={[
                { header: 'Build', render: (v) => <span className="font-mono text-xs">{v.parserVersion}</span> },
                { header: 'Imports', render: (v) => <span className="font-mono">{count(v.jobs)}</span> },
                { header: 'Clean', render: (v) => <span className="font-mono">{count(v.clean)}</span> },
                { header: 'Review recommended', render: (v) => <span className="font-mono">{count(v.reviewRecommended)}</span> },
                { header: 'Needs attention', render: (v) => <span className="font-mono">{count(v.needsAttention)}</span> },
              ]}
              rows={data.byParserVersion}
              keyFor={(v) => v.parserVersion}
              loading={false}
              emptyMessage="No import has check results yet."
            />
          </div>
        </>
      )}
    </section>
  );
}

function reliabilityLabel(status: string) {
  switch (status) {
    case 'CLEAN': return 'Clean';
    case 'REVIEW_RECOMMENDED': return 'Review recommended';
    case 'NEEDS_ATTENTION': return 'Needs attention';
    default: return status;
  }
}

function textSourceLabel(source: string) {
  switch (source) {
    case 'NATIVE_PDF': return 'Text in the file';
    case 'OCR': return 'Scanned (OCR)';
    case 'NATIVE_PLUS_OCR': return 'Text plus OCR';
    case 'UNKNOWN': return 'Not recorded';
    default: return source;
  }
}

/** What each dry-run outcome means, from StatementRefreshDryRunService. */
const REFRESH_STATUS: Record<string, { label: string; meaning: string }> = {
  CHANGES: { label: 'Changes found', meaning: 'Re-reading gives different rows; the customer is offered a refresh.' },
  NO_CHANGES: { label: 'No changes', meaning: 'Re-reading gives the same rows and details.' },
  NEEDS_REVIEW: {
    label: 'Held for review',
    meaning: 'Changes found, but a trust check held them or they remove too many rows, so no refresh is offered.',
  },
  NEEDS_PASSWORD: { label: 'Needs password', meaning: 'The file is password-protected and no password is saved.' },
  FAILED: { label: 'Could not check', meaning: 'The stored file could not be read or the new build refused it.' },
};

type RefreshRow = StatementRefreshSummary['byStatus'][number];

const REFRESH_COLUMNS: DataTableColumn<RefreshRow>[] = [
  {
    header: 'Outcome',
    render: (r) => (
      <div>
        <p className="text-ink font-medium">{REFRESH_STATUS[r.status]?.label ?? r.status}</p>
        {REFRESH_STATUS[r.status] && <p className="text-xs text-muted">{REFRESH_STATUS[r.status].meaning}</p>}
      </div>
    ),
  },
  { header: 'Statements', render: (r) => <span className="font-mono">{count(r.statements)}</span> },
  { header: 'Customers', render: (r) => <span className="font-mono">{count(r.users)}</span> },
  {
    header: 'Rows changed / added / removed',
    render: (r) => <span className="font-mono">{count(r.rowsChanged)} / {count(r.rowsAdded)} / {count(r.rowsRemoved)}</span>,
  },
  { header: 'Conflicts', render: (r) => <span className="font-mono">{count(r.rowsConflicting)}</span> },
  { header: 'Details changed', render: (r) => <span className="font-mono">{count(r.factsChanged)}</span> },
];

function RefreshSection() {
  const [draft, setDraft] = useState('');
  const [version, setVersion] = useState('');
  const refresh = useQuery({
    queryKey: ['import-health-refresh', version],
    queryFn: () => adminImportHealthApi.refresh(version || undefined),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setVersion(draft.trim());
  };

  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-ink">Statement re-check</h3>
          <p className="text-xs text-muted">
            After each deploy, every statement an older build read is read again in the background, without
            touching anyone's transactions. This is what that found.
          </p>
        </div>
        <form onSubmit={submit} className="flex items-center gap-2">
          <label htmlFor="refresh-build" className="text-xs text-muted">Build</label>
          <input
            id="refresh-build"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Running build"
            className="bg-card border border-border rounded-lg px-2 py-1 text-sm font-mono w-36"
          />
          <button type="submit" className="text-xs font-semibold text-primary px-2 py-1">Show</button>
        </form>
      </div>
      {refresh.data && refresh.data.parserVersion == null && (
        <p className="text-sm text-muted">This server doesn't know which build it's running, so there's nothing to compare.</p>
      )}
      {refresh.data?.parserVersion != null && (
        <p className="text-sm text-ink">
          Build <span className="font-mono">{refresh.data.parserVersion}</span>
          {version === '' && ' (running now)'}:{' '}
          {refresh.data.statementsAwaitingCheck === 0
            ? 'nothing is waiting to be checked.'
            : `${count(refresh.data.statementsAwaitingCheck)} statements still waiting to be checked.`}
        </p>
      )}
      {(refresh.isLoading || refresh.error != null || refresh.data?.parserVersion != null) && (
        <DataTable
          columns={REFRESH_COLUMNS}
          rows={refresh.data?.byStatus}
          keyFor={(r) => r.status}
          loading={refresh.isLoading}
          error={refresh.error}
          emptyMessage="No statement has been checked against this build yet."
        />
      )}
    </section>
  );
}

function ImportHealthContent() {
  return (
    <div className="space-y-10">
      <FailuresSection />
      <TelemetrySection />
      <RefreshSection />
    </div>
  );
}

export default function ImportHealth() {
  return (
    <AdminLayout title="Import Health" subtitle="How customers' statement imports are going. Counts only, no customer data.">
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <ImportHealthContent />
      </RequirePermission>
    </AdminLayout>
  );
}
