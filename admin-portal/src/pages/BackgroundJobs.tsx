import { useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { ChevronDown, ChevronRight, RefreshCw } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { LoadError } from '../components/LoadError';
import { adminBackgroundJobsApi } from '../api/endpoints';
import { formatIst } from '../lib/istTime';
import type { BackgroundJobDto, BackgroundJobState } from '../types';

/**
 * Background jobs: every scheduled job the backend runs on its own (account deletion, notification
 * sending, overdue-hold alerts, subscription checks...), broken ones first. A job that crashed,
 * partly failed or missed its schedule otherwise fails customers quietly, visible only in logs.
 *
 * Built for a phone: one column, whole rows to tap. `?job=<key>` (the alert email's link) opens that
 * job and scrolls it into view.
 */

const STATE_LABEL: Record<BackgroundJobState, { label: string; className: string }> = {
  CRASHED: { label: 'Crashed', className: 'text-danger bg-danger-bg' },
  PARTLY_FAILED: { label: 'Partly failed', className: 'text-danger bg-danger-bg' },
  OVERDUE: { label: 'Overdue', className: 'text-danger bg-danger-bg' },
  OFF: { label: 'Off', className: 'text-muted bg-bg' },
  OK: { label: 'OK', className: 'text-success bg-success-bg' },
};

function needsAttention(state: BackgroundJobState): boolean {
  return state === 'CRASHED' || state === 'PARTLY_FAILED' || state === 'OVERDUE';
}

/** One line on what is going on with the job, for the row itself. */
export function jobSummary(job: BackgroundJobDto): string {
  switch (job.state) {
    case 'CRASHED':
      return job.consecutiveFailures > 1 ? `${job.consecutiveFailures} runs in a row failed` : 'Last run crashed';
    case 'PARTLY_FAILED': {
      const items = job.lastHandled > 0
        ? `${job.lastFailedItems} of ${job.lastHandled} items failed`
        : `${job.lastFailedItems} ${job.lastFailedItems === 1 ? 'item' : 'items'} failed`;
      return job.consecutiveFailures > 1 ? `${items} · ${job.consecutiveFailures} runs in a row` : `${items} in the last run`;
    }
    case 'OVERDUE':
      if (job.running) return `Running since ${formatIst(job.lastStartedAt)}, longer than it should`;
      return job.lastFinishedAt ? `Hasn't run since ${formatIst(job.lastFinishedAt)}` : "Hasn't run since the server started";
    case 'OFF':
      return 'Switched off on purpose';
    case 'OK':
      return job.lastFinishedAt ? `Last run ${formatIst(job.lastFinishedAt)}` : 'Not run yet since the server started';
  }
}

function FailureHistory({ jobKey }: { jobKey: string }) {
  const { data, error, isLoading } = useQuery({
    queryKey: ['admin-background-job-failures', jobKey],
    queryFn: () => adminBackgroundJobsApi.failures(jobKey),
  });
  if (isLoading) return <p className="text-sm text-muted">Loading failures…</p>;
  if (!data) return <LoadError error={error} what="this job's failures" />;
  if (data.length === 0) return <p className="text-sm text-muted">No failures in the last 30 days.</p>;
  return (
    <>
    {data.length >= 50 && <p className="text-xs text-muted mb-2">The 50 most recent are shown.</p>}
    <ul className="space-y-2">
      {data.map((f) => (
        <li key={f.occurredAt + (f.errorSummary ?? '')} className="text-sm border border-border rounded-lg px-3 py-2">
          <p className="font-medium text-ink">
            {formatIst(f.occurredAt)} · {f.outcome === 'CRASHED' ? 'Crashed' : `${f.failedItems} ${f.failedItems === 1 ? 'item' : 'items'} failed`}
          </p>
          {f.errorSummary && <p className="text-muted break-words font-mono text-xs mt-0.5">{f.errorSummary}</p>}
        </li>
      ))}
    </ul>
    </>
  );
}

function JobRow({ job, open, onToggle }: { job: BackgroundJobDto; open: boolean; onToggle: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const state = STATE_LABEL[job.state];
  return (
    <div data-testid="background-job" ref={ref} id={`job-${job.key}`}
      className="bg-card border border-border rounded-xl2 shadow-card">
      <button type="button" onClick={onToggle} aria-expanded={open}
        className="w-full text-left px-4 py-3.5 flex items-start gap-3 min-h-[44px]">
        {open ? <ChevronDown size={16} className="mt-1 shrink-0 text-muted" /> : <ChevronRight size={16} className="mt-1 shrink-0 text-muted" />}
        <span className="flex-1 min-w-0">
          <span className="flex flex-wrap items-center gap-2">
            <span data-testid="job-name" className="font-semibold text-ink">{job.name}</span>
            <span className={`text-xs font-semibold rounded-full px-2.5 py-0.5 ${state.className}`}>{state.label}</span>
          </span>
          <span className="block text-sm text-muted mt-0.5">{jobSummary(job)}</span>
        </span>
      </button>
      {open && (
        <div className="px-4 pb-4 pt-1 space-y-3 border-t border-border">
          <p className="text-sm text-ink">{job.purpose}</p>
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-1 text-sm">
            <div><dt className="inline text-muted">Runs: </dt><dd className="inline text-ink">{job.schedule}</dd></div>
            <div><dt className="inline text-muted">Last run: </dt><dd className="inline text-ink">{formatIst(job.lastFinishedAt ?? job.lastStartedAt)}</dd></div>
            <div><dt className="inline text-muted">Last success: </dt><dd className="inline text-ink">{formatIst(job.lastSuccessAt)}</dd></div>
            {job.brokenSince && (
              <div><dt className="inline text-muted">Broken since: </dt><dd className="inline text-ink">{formatIst(job.brokenSince)}</dd></div>
            )}
          </dl>
          <div>
            <h3 className="text-xs font-semibold text-muted uppercase tracking-wide mb-2">Failures, last 30 days</h3>
            <FailureHistory jobKey={job.key} />
          </div>
        </div>
      )}
    </div>
  );
}

function BackgroundJobsContent() {
  const [params, setParams] = useSearchParams();
  const openKey = params.get('job');
  const scrolledFor = useRef<string | null>(null);
  const { data, error, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['admin-background-jobs'],
    queryFn: () => adminBackgroundJobsApi.list(),
    refetchInterval: 60_000,
  });

  useEffect(() => {
    if (!data || !openKey || scrolledFor.current === openKey) return;
    scrolledFor.current = openKey;
    document.getElementById(`job-${openKey}`)?.scrollIntoView?.({ block: 'start', behavior: 'smooth' });
  }, [data, openKey]);

  if (isLoading) return <p className="text-muted text-sm">Loading…</p>;
  if (!data) return <LoadError error={error} what="background jobs" />;

  const broken = data.filter((j) => needsAttention(j.state)).length;
  const toggle = (key: string) => {
    const next = new URLSearchParams(params);
    if (openKey === key) next.delete('job'); else next.set('job', key);
    setParams(next, { replace: true });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <p className={`text-sm font-semibold ${broken > 0 ? 'text-danger' : 'text-success'}`}>
          {broken === 0 ? 'Every job is running normally' : `${broken} ${broken === 1 ? 'job needs' : 'jobs need'} attention`}
        </p>
        <button type="button" onClick={() => refetch()} disabled={isFetching}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-ink border border-border rounded-lg px-4 py-3 sm:px-3.5 sm:py-2 hover:bg-bg disabled:opacity-50">
          <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>
      <div className="space-y-3">
        {data.map((job) => (
          <JobRow key={job.key} job={job} open={openKey === job.key} onToggle={() => toggle(job.key)} />
        ))}
      </div>
    </div>
  );
}

export default function BackgroundJobs() {
  return (
    <AdminLayout title="Background jobs" subtitle="Work the backend runs on its own schedule, broken first">
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <BackgroundJobsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
