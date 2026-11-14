import { useEffect, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { LoadError } from '../components/LoadError';
import { describeClaudeFix } from '../components/ClaudeFixLine';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminDecisionQueueApi } from '../api/endpoints';
import { formatIst } from '../lib/istTime';
import { timeLeftLabel } from '../lib/timeLeft';
import type { DecisionQueueRow } from '../types';

const KIND_LABEL: Record<DecisionQueueRow['kind'], string> = {
  IMPORT: 'Held import',
  STATEMENT: 'Held statement',
  NO_RECORD: 'Held, no review record',
};

/** The next step's words. The server picks the step; the words live here. */
export function nextStepText(row: DecisionQueueRow): string {
  switch (row.nextStep) {
    case 'OPEN_REVIEW': return 'Open a review for it';
    case 'APPROVE_RERUN_PASSED': return 'The re-run passed: approve it';
    case 'MERGE_PR': return row.prNumber ? `Review and merge PR #${row.prNumber}` : "Review and merge Claude's PR";
    case 'CLAUDE_WORKING': return 'Claude is working on a fix';
    case 'CLAUDE_REREADING': return 'Fix is live: Claude is re-reading it';
    case 'CLAUDE_NOT_STARTED': return 'Claude has not started yet';
    case 'CLAUDE_DID_NOT_START':
      return row.kind === 'IMPORT'
        ? 'Claude did not start: fix the reader and reprocess, or resolve with a message'
        : 'Claude did not start: check the evidence, then approve or reject';
    case 'CLAUDE_STALLED': return "Claude's run stopped reporting back: check Background jobs, then handle it by hand";
    case 'ADMIN_LOOKING': return `${row.whoName ?? 'An admin'} is looking into it`;
    case 'FIX_AND_REPROCESS': return 'Fix the reader and reprocess, or resolve with a message';
    case 'CHECK_AND_DECIDE': return 'Check the evidence, then approve or reject';
    default: return 'Open it to decide';
  }
}

export function whoText(row: DecisionQueueRow): string {
  if (row.who === 'ADMIN') return row.whoName ?? 'An admin';
  if (row.who === 'CLAUDE') return 'Claude';
  return 'Nobody yet';
}

/** Where the hold is decided today: this page links there and decides nothing itself. */
export function openLink(row: DecisionQueueRow): string {
  if (row.kind === 'IMPORT') return `/held-imports?job=${encodeURIComponent(row.jobId)}`;
  if (row.kind === 'STATEMENT' && row.heldId) return `/held-statements/${encodeURIComponent(row.heldId)}`;
  return '/held-statements';
}

/** The Claude line. After the "Claude:" label the shared words' "Claude fix off" would say Claude
 *  twice; and a run that stopped reporting must not still read "working on it" under a next step
 *  saying it stopped. */
export function claudeLine(row: DecisionQueueRow): ReactNode {
  const fix = row.claudeFix;
  if (!fix) return null;
  if (fix.state === 'OFF') return 'off';
  if (row.nextStep === 'CLAUDE_STALLED') return `Attempt ${fix.attempt} of 2 sent, nothing back for over 4 hours`;
  return describeClaudeFix(fix);
}

function Row({ row, now }: { row: DecisionQueueRow; now: number }) {
  const left = timeLeftLabel(row.promiseAt, now);
  const claude = claudeLine(row);
  return (
    <li className={`rounded-lg border p-4 bg-card ${left.past ? 'border-danger border-l-4' : 'border-border'}`}
      data-testid="decision-row">
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1">
        <div className="min-w-0">
          <p className="text-xs font-medium text-muted">
            {KIND_LABEL[row.kind]}
            {row.bank ? ` · ${row.bank}` : row.sourceFormat ? ` · ${row.sourceFormat}` : ''}
          </p>
          <p className="text-sm font-semibold text-ink mt-1">{nextStepText(row)}</p>
        </div>
        <p className={`text-sm font-semibold whitespace-nowrap ${left.past ? 'text-danger' : 'text-ink'}`}>{left.text}</p>
      </div>
      <dl className="mt-2 grid gap-1 text-xs sm:grid-cols-2">
        <div><dt className="inline text-muted">On it: </dt>
          <dd className={`inline ${row.who === 'NOBODY' ? 'text-warning font-medium' : 'text-ink'}`}>{whoText(row)}</dd></div>
        <div><dt className="inline text-muted">Held since: </dt><dd className="inline text-ink">{formatIst(row.heldAt)}</dd></div>
        {claude && (
          <div className="sm:col-span-2"><dt className="inline text-muted">Claude: </dt><dd className="inline text-ink">{claude}</dd></div>
        )}
        {row.alsoDecides > 0 && (
          <div className="sm:col-span-2"><dt className="inline text-muted">Also decides: </dt>
            <dd className="inline text-ink">
              {row.alsoDecides} {row.alsoDecides === 1 ? 're-upload' : 're-uploads'} of this statement
            </dd></div>
        )}
      </dl>
      <Link to={openLink(row)}
        className="mt-3 inline-flex items-center text-sm font-medium text-primary border border-border rounded-lg px-4 py-3 sm:px-3.5 sm:py-2 hover:bg-bg">
        Open
      </Link>
    </li>
  );
}

/** The time now, moving on every 30 seconds, so a row's time left keeps counting down between
 *  fetches and while a refresh is failing. */
function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(id);
  }, []);
  return now;
}

function NeedsADecisionContent({ seesImports, seesStatements }: { seesImports: boolean; seesStatements: boolean }) {
  const { data, error, isError, isLoading, refetch, isFetching, dataUpdatedAt } = useQuery({
    queryKey: ['admin-decision-queue'],
    queryFn: () => adminDecisionQueueApi.list(),
    refetchInterval: 60_000,
  });
  const now = useNow();

  if (isLoading) return <p className="text-muted text-sm">Loading…</p>;
  if (!data) return <LoadError error={error} what="the holds waiting on a decision" />;

  const nobody = data.filter((r) => r.who === 'NOBODY').length;
  const past = data.filter((r) => timeLeftLabel(r.promiseAt, now).past).length;
  const onlyOneKind = seesImports !== seesStatements;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <p className={`text-sm font-semibold ${past > 0 ? 'text-danger' : nobody > 0 ? 'text-warning' : 'text-success'}`}>
          {data.length === 0
            ? 'Nothing is waiting on a decision.'
            : `${data.length} waiting · ${nobody} with nobody on it${past > 0 ? ` · ${past} past the promise` : ''}`}
        </p>
        <button type="button" onClick={() => refetch()} disabled={isFetching}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-ink border border-border rounded-lg px-4 py-3 sm:px-3.5 sm:py-2 hover:bg-bg disabled:opacity-50">
          <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>
      {isError && (
        <p role="alert" className="text-sm text-danger">
          Could not refresh. This list is from {formatIst(new Date(dataUpdatedAt).toISOString())} and may be out of date.
        </p>
      )}
      {onlyOneKind && (
        <p className="text-sm text-muted">
          {seesImports
            ? 'Showing held imports only. Held statements need the TRUST_REVIEW_MANAGE permission.'
            : 'Showing held statements only. Held imports need the IMPORT_TRIAGE_MANAGE permission.'}
        </p>
      )}
      {data.length > 0 && (
        <ul className="space-y-3">
          {data.map((row) => <Row key={row.jobId} row={row} now={now} />)}
        </ul>
      )}
    </div>
  );
}

/**
 * Every open hold the admin may decide -- held imports and held statements together -- with the
 * ones nobody is on first, then the least time left on the 48-hour promise. Each row says who is
 * on it, the next step and what the Claude fix did, and opens the hold's own page, where it is
 * decided (docs/superpowers/specs/2026-11-14-needs-a-decision-design.md).
 */
export default function NeedsADecision() {
  const { hasPermission } = useAdminAuth();
  const seesImports = hasPermission('IMPORT_TRIAGE_MANAGE');
  const seesStatements = hasPermission('TRUST_REVIEW_MANAGE');
  return (
    <AdminLayout title="Needs a decision" subtitle="Every open hold: nobody-on-it first, then least time left on the 48-hour promise">
      {seesImports || seesStatements
        ? <NeedsADecisionContent seesImports={seesImports} seesStatements={seesStatements} />
        : <p className="text-muted text-sm">This requires the IMPORT_TRIAGE_MANAGE or TRUST_REVIEW_MANAGE permission.</p>}
    </AdminLayout>
  );
}
