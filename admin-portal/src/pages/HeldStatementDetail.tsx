import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Eye } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminBlankedCopyApi, adminHeldStatementApi } from '../api/endpoints';
import BlankedCopyPanel from '../components/BlankedCopyPanel';
import OpeningReasonDialog from '../components/OpeningReasonDialog';
import OriginalStatementViewer from '../components/OriginalStatementViewer';
import { BALANCE_MISMATCH_KEYS, BalanceMismatchList, balanceMismatchShapes } from '../components/BalanceMismatchList';
import { formatWhen } from '../lib/formatWhen';
import type { HeldStatementFinding, HeldStatementRerunResult, HeldStatementStagedRows } from '../types';

const RESOLVED_STATUSES = new Set(['IMPORTED', 'REJECTED']);

function onActionErrorMessage(error: unknown): string {
  const response = (error as { response?: { data?: { message?: string } } })?.response;
  return response?.data?.message ?? 'That action could not be completed.';
}

/** A detail value is a count, a boolean, a string, or -- for one rule's histogram
 *  (`droppedTransactionCandidateReasons`) -- a reason-code-to-count object; `mismatches` is a
 *  string list. `String(value)` renders an object as the literal, useless "[object Object]", so
 *  anything non-primitive is JSON-stringified instead -- not pretty, but legible, which is what a
 *  diagnostic view over an intentionally-unshaped allowlisted map needs. */
function formatDetailValue(value: unknown): string {
  if (value === null || value === undefined) return '—';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

/** One finding's evidence as key: value pairs -- the numbers behind the trigger, not a sentence
 *  summarising them. `details` is an arbitrary allowlisted map from the backend, so this renders
 *  whatever keys are actually present rather than assuming a fixed shape. */
function FindingCard({ finding }: { finding: HeldStatementFinding }) {
  // A balance mismatch's rows are described in sentences below the pairs, by the same component
  // Import Trace uses, rather than printed here as a line of JSON. Left in the pairs only when
  // there is nothing readable in them, so a shape this version cannot describe is still visible.
  const described = balanceMismatchShapes(finding.details).length > 0;
  const entries = Object.entries(finding.details)
    .filter(([key]) => !(described && BALANCE_MISMATCH_KEYS.includes(key)));
  return (
    <div className="rounded-lg border border-border bg-bg p-3">
      <div className="flex items-center justify-between">
        <span className="text-ink font-mono text-xs font-medium">{finding.rule}</span>
        <span className="text-xs text-warning">{finding.outcome}</span>
      </div>
      {entries.length > 0 && (
        // One pair per row below sm: two columns at phone width left each value about 60px, so a
        // four-digit amount broke across two lines.
        <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-1 mt-2 text-xs">
          {entries.map(([key, value]) => (
            <div key={key} className="flex justify-between gap-2">
              <dt className="text-muted">{key}</dt>
              <dd className="text-ink font-mono break-all">{formatDetailValue(value)}</dd>
            </div>
          ))}
        </dl>
      )}
      <BalanceMismatchList
        details={finding.details}
        rowNumbersNote={`Row numbers are the # column of section ${finding.sectionIndex + 1}’s staged rows on this page.`}
      />
    </div>
  );
}

/** The rows a hold staged, one table per account section -- what approving releases. */
function StagedRowsView({ data }: { data: HeldStatementStagedRows }) {
  if (data.sections.length === 0) {
    return <p className="text-xs text-muted">This hold staged no rows.</p>;
  }
  return (
    <div className="space-y-4" data-testid="staged-rows">
      {data.sections.map((section, index) => (
        <div key={index} className="space-y-2">
          <p className="text-xs text-muted">
            <span className="text-ink font-medium">{section.accountName ?? `Section ${index + 1}`}</span>
            {section.accountNumberMasked && <> &middot; {section.accountNumberMasked}</>}
            {section.statementPeriodStart && section.statementPeriodEnd && (
              <> &middot; {section.statementPeriodStart} to {section.statementPeriodEnd}</>
            )}
            {' '}&middot; {section.rows.length} staged of {section.totalParsed} read
          </p>
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="text-left text-muted">
                  {/* The number a balance-mismatch finding names a row by, so "Row 13" above can
                      be found without counting. */}
                  <th className="py-1 pr-3 font-normal text-right">#</th>
                  <th className="py-1 pr-3 font-normal">Date</th>
                  <th className="py-1 pr-3 font-normal">Description</th>
                  <th className="py-1 pr-3 font-normal text-right">Amount</th>
                  <th className="py-1 pr-3 font-normal">Type</th>
                  <th className="py-1 pr-3 font-normal text-right">Balance</th>
                </tr>
              </thead>
              <tbody>
                {section.rows.map((row, rowIndex) => (
                  <tr key={rowIndex} className="border-t border-border">
                    <td className="py-1 pr-3 font-mono text-right text-muted">{rowIndex + 1}</td>
                    <td className="py-1 pr-3 font-mono whitespace-nowrap">{row.date ?? '—'}</td>
                    <td className="py-1 pr-3 text-ink">{row.description ?? '—'}</td>
                    <td className="py-1 pr-3 font-mono text-right">{row.amount ?? '—'}</td>
                    <td className="py-1 pr-3">{row.type ?? '—'}</td>
                    <td className="py-1 pr-3 font-mono text-right">{row.balanceAfter ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ))}
    </div>
  );
}

/** Readable names for History entries that are not the hold's own state changes. The rest keep
 *  their event type as before. */
const EVENT_LABEL: Record<string, string> = {
  ORIGINAL_VIEWED: 'Original statement opened',
  STAGED_ROWS_VIEWED: 'Staged rows opened',
};

function HeldStatementDetailContent({ heldId }: { heldId: string }) {
  const { roles } = useAdminAuth();
  // The real statement and its staged rows are pinned to these roles on the server too.
  const canViewOriginal = roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
  const queryClient = useQueryClient();

  const [actionError, setActionError] = useState<string | null>(null);
  const [viewingOriginal, setViewingOriginal] = useState(false);
  const [askingForRows, setAskingForRows] = useState(false);
  const [approveNote, setApproveNote] = useState('');
  const [markFalsePositive, setMarkFalsePositive] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [reopenReason, setReopenReason] = useState('');
  const [engineerIdInput, setEngineerIdInput] = useState('');
  const [notesDraft, setNotesDraft] = useState('');
  const [rootCauseDraft, setRootCauseDraft] = useState('');
  const [fixReferenceDraft, setFixReferenceDraft] = useState('');
  const [rerunResult, setRerunResult] = useState<HeldStatementRerunResult | null>(null);
  const [stagedRows, setStagedRows] = useState<HeldStatementStagedRows | null>(null);

  const detail = useQuery({
    queryKey: ['held-statement-detail', heldId],
    queryFn: () => adminHeldStatementApi.get(heldId),
  });
  // Whether the server's blanked-copy switch is on. The copy is offered to anyone working this
  // queue, not only the admin roles the real document is pinned to: it is blanked.
  const blankedStatus = useQuery({ queryKey: ['blanked-copy-status'], queryFn: adminBlankedCopyApi.status });

  // Pre-fills the notes/findings editors with what is already on the row, once, when the row
  // first loads -- not on every refetch, or an operator's in-progress edit would be clobbered the
  // moment their own save triggers this same query to refresh. markFalsePositive is included for a
  // different reason: without this, the checkbox always renders unchecked, even for an already-
  // resolved hold that WAS marked false positive at approve time -- contradicting the timeline
  // entry on the very same screen. For an unresolved hold `falsePositive` is always null, so `??
  // false` still defaults to unchecked there.
  useEffect(() => {
    if (detail.data) {
      setNotesDraft(detail.data.summary.engineerNotes ?? '');
      setRootCauseDraft(detail.data.summary.rootCause ?? '');
      setFixReferenceDraft(detail.data.summary.fixReference ?? '');
      setMarkFalsePositive(detail.data.summary.falsePositive ?? false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail.data?.summary.id]);

  // A previous held statement's rerun result must never linger on screen once navigation moves to
  // a different one -- "clears under the current parser build" shown against the wrong row is
  // actively misleading, not just stale. Keyed on the route param itself (not detail.data), so the
  // reset happens the instant navigation occurs rather than waiting for the new query to resolve.
  useEffect(() => {
    setRerunResult(null);
    setStagedRows(null);
  }, [heldId]);

  // Same stale-state class of bug the rerun result above already guards against: a checked box
  // left over from a previous hold must never silently ride along into the next approve call on a
  // different row. This fires the instant navigation occurs, before the new query resolves; the
  // effect above then corrects it to the new row's real persisted value once data loads.
  useEffect(() => {
    setMarkFalsePositive(false);
  }, [heldId]);

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['held-statement-detail', heldId] });
    void queryClient.invalidateQueries({ queryKey: ['held-statements-list'] });
  }

  function onError(error: unknown) {
    setActionError(onActionErrorMessage(error));
  }

  const approve = useMutation({
    // markFalsePositive || undefined, not the bare boolean -- the checkbox defaults to false, so
    // passing it straight through would send an explicit `false` on every approval where the
    // operator never touched it, indistinguishable from "explicitly reviewed and confirmed not a
    // false positive." Only `true` (checked) is ever a real signal from this control; unchecked
    // must reach the backend as an absent field, so the nullable `Boolean falsePositive` stays
    // reachable as `null`.
    mutationFn: () => adminHeldStatementApi.approve(
        heldId, approveNote || undefined, markFalsePositive || undefined),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const reject = useMutation({
    mutationFn: () => adminHeldStatementApi.reject(heldId, rejectReason || undefined),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const reopen = useMutation({
    mutationFn: () => adminHeldStatementApi.reopen(heldId, reopenReason || undefined),
    onSuccess: () => { setActionError(null); setReopenReason(''); invalidate(); },
    onError,
  });
  const assignToMe = useMutation({
    mutationFn: () => adminHeldStatementApi.assign(heldId, undefined),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const assignToEngineer = useMutation({
    mutationFn: () => adminHeldStatementApi.assign(heldId, engineerIdInput.trim()),
    onSuccess: () => { setActionError(null); setEngineerIdInput(''); invalidate(); },
    onError,
  });
  const investigate = useMutation({
    mutationFn: () => adminHeldStatementApi.investigate(heldId),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const saveNotes = useMutation({
    mutationFn: () => adminHeldStatementApi.notes(heldId, notesDraft),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const saveFindings = useMutation({
    mutationFn: () => adminHeldStatementApi.saveFindings(heldId, rootCauseDraft || undefined,
      fixReferenceDraft || undefined),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });
  const rerunParser = useMutation({
    mutationFn: () => adminHeldStatementApi.rerunParser(heldId),
    onSuccess: (result) => { setActionError(null); setRerunResult(result); invalidate(); },
    onError,
  });
  // No local result state, unlike rerunParser above: the mutation returns the whole detail, and
  // invalidate() refetches it -- summary.aiSuggestedDiagnosis then just renders from the query,
  // the same way engineerNotes/rootCause already do below.
  const suggestDiagnosis = useMutation({
    mutationFn: () => adminHeldStatementApi.suggestDiagnosis(heldId),
    onSuccess: () => { setActionError(null); invalidate(); },
    onError,
  });

  if (detail.isLoading) {
    return <p className="text-muted text-sm">Loading…</p>;
  }
  if (detail.isError || !detail.data) {
    return <p className="text-muted text-sm">No such held statement.</p>;
  }

  const { summary, findings, timeline } = detail.data;
  const resolved = RESOLVED_STATUSES.has(summary.status);
  // A locked PDF staged without its password being kept: nobody can open the stored file, so
  // the original and re-run are not offered and the rows below are the review.
  const locked = summary.lockedWithoutPassword === true;
  const busy = approve.isPending || reject.isPending || reopen.isPending || assignToMe.isPending
    || assignToEngineer.isPending || investigate.isPending || rerunParser.isPending
    || suggestDiagnosis.isPending;

  return (
    <div className="space-y-6">
      <Link to="/held-statements" className="text-xs text-primary hover:underline">
        &larr; Back to queue
      </Link>

      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-ink font-mono">{summary.heldId}</h2>
          <p className="text-muted text-xs mt-1">{summary.status.replace(/_/g, ' ')}</p>
        </div>
        <div className="flex items-start gap-3">
          {/* The held-statement detail carries no format, so a CSV hold is offered the copy too and
              the server answers it with its own 409. A decided or locked hold is not offered it:
              the server refuses both, and the reason is already on the page. */}
          {blankedStatus.data?.enabled && !resolved && !locked && (
            <BlankedCopyPanel key={heldId} fetchCopy={() => adminBlankedCopyApi.heldStatement(heldId)} />
          )}
          {/* Only an open hold can be opened (the server refuses a decided one with a 409), and a
              locked file without its password cannot be opened at all. */}
          {canViewOriginal && !locked && !resolved && (
            <button
              type="button"
              onClick={() => setViewingOriginal(true)}
              className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-1.5 text-xs text-ink hover:bg-card"
            >
              <Eye className="h-3.5 w-3.5" />
              View original
            </button>
          )}
        </div>
      </div>
      {canViewOriginal && !locked && !resolved && (
        <p className="text-xs text-muted">
          Viewing the original needs a reason. It is logged against your account, the Super Admins are
          emailed, and the customer is told the date.
        </p>
      )}
      {/* The rows are the statement's content, so they take the same rules as the original: a
          reason first, then the Super Admins are emailed and the customer is told. */}
      {askingForRows && (
        <OpeningReasonDialog
          title="Show the staged rows"
          confirmLabel="Show rows"
          failureText="The staged rows could not be shown."
          onOpen={async (reason) => {
            setStagedRows(await adminHeldStatementApi.stagedRows(heldId, reason));
            setActionError(null);
            setAskingForRows(false);
          }}
          onClose={() => setAskingForRows(false)}
        />
      )}
      {viewingOriginal && (
        <OriginalStatementViewer queue="TRUST" itemId={heldId} onClose={() => setViewingOriginal(false)} />
      )}
      {locked && (
        <div className="rounded-lg border border-warning p-3" data-testid="locked-without-password">
          <p className="text-sm text-ink">This statement is password-protected, and its password wasn&apos;t kept.</p>
          <p className="text-xs text-muted mt-1">
            The user uploaded it without letting Fynora keep the password, so the stored file can&apos;t be
            opened and the parser can&apos;t read it again. Review the staged rows and the findings below,
            then approve or reject. If it needs a fresh read, reject it and ask the user to upload it again.
          </p>
        </div>
      )}

      {actionError && (
        <div className="rounded-lg bg-danger-bg p-3">
          <p className="text-sm text-danger">{actionError}</p>
        </div>
      )}

      {/* Trigger evidence */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
        <h3 className="text-sm font-semibold text-ink">Why this fired</h3>
        <p className="text-sm text-muted">{summary.triggerSummary ?? '—'}</p>
        {findings.length > 0 && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {findings.map((finding) => (
              <FindingCard key={`${finding.sectionIndex}-${finding.rule}`} finding={finding} />
            ))}
          </div>
        )}
      </section>

      {/* Staged rows */}
      {canViewOriginal && (
        <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
          <h3 className="text-sm font-semibold text-ink">Staged rows</h3>
          <p className="text-xs text-muted">
            The rows approving releases to the user&apos;s confirm step. They are content from a
            customer&apos;s bank statement, so viewing them needs a reason, is logged against your
            account, emails the Super Admins and tells the customer the date.
          </p>
          {stagedRows ? (
            <StagedRowsView data={stagedRows} />
          ) : (
            <button
              type="button"
              onClick={() => setAskingForRows(true)}
              className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink"
            >
              Show staged rows
            </button>
          )}
        </section>
      )}

      {/* Extraction snapshot */}
      <section className="bg-card border border-border rounded-xl2 p-6">
        <h3 className="text-sm font-semibold text-ink mb-3">Extraction snapshot</h3>
        <dl className="grid grid-cols-2 sm:grid-cols-3 gap-4 text-sm">
          <div>
            <dt className="text-muted text-xs">User</dt>
            <dd className="text-ink font-mono text-xs">{summary.userId}</dd>
          </div>
          <div>
            <dt className="text-muted text-xs">Bank</dt>
            <dd className="text-ink">{summary.bankName ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted text-xs">Parser version</dt>
            <dd className="text-ink font-mono text-xs">{summary.parserVersion ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted text-xs">Reliability</dt>
            <dd className="text-ink">{summary.reliabilityStatus ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted text-xs">Text source</dt>
            <dd className="text-ink">{summary.textSource ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted text-xs">Header reconstruction uncertain</dt>
            <dd className="text-ink">
              {summary.headerReconstructionUncertain === null ? '—'
                : summary.headerReconstructionUncertain ? 'Yes' : 'No'}
            </dd>
          </div>
        </dl>
      </section>

      {/* Parser re-run */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
        <h3 className="text-sm font-semibold text-ink">Re-run parser</h3>
        {locked ? (
          <p className="text-xs text-muted">
            Not available: the statement is password-protected and its password wasn&apos;t kept, so the
            parser can&apos;t open it.
          </p>
        ) : (
          <p className="text-xs text-muted">
            Re-parses this statement's original bytes with the parser build running right now, and
            checks whether it would still be flagged. If it clears, its rows replace the ones staged
            when the statement was held, so approving releases this build&apos;s reading. If it is
            still flagged, nothing changes.
          </p>
        )}
        <button
          type="button"
          onClick={() => rerunParser.mutate()}
          disabled={busy || resolved || locked}
          className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
        >
          {rerunParser.isPending ? 'Re-running…' : 'Re-run parser'}
        </button>
        {rerunResult && (
          <div className="rounded-lg border border-border bg-bg p-3 text-xs space-y-1">
            <p className={rerunResult.stillHeld ? 'text-warning' : 'text-success'}>
              {rerunResult.stillHeld
                ? `Still held: ${rerunResult.reasons.join('; ')}`
                : 'Clears under the current parser build.'}
            </p>
            {rerunResult.parserVersionChanged && (
              <p className="text-muted font-mono">
                {rerunResult.previousParserVersion || '—'} &rarr; {rerunResult.currentParserVersion || '—'}
              </p>
            )}
          </div>
        )}
      </section>

      {/* Fyn: import diagnosis assist */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-3">
        <h3 className="text-sm font-semibold text-ink">Suggest diagnosis (Fyn)</h3>
        <p className="text-xs text-muted">
          Asks Fyn for a likely root cause from structural signals alone -- parser version, which
          trust-predicate rules fired, and this hold's summary. Never sends the statement's actual
          content. Not a fix, and not guaranteed correct -- narrows where to look first.
        </p>
        <button
          type="button"
          onClick={() => suggestDiagnosis.mutate()}
          disabled={busy}
          className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
        >
          {suggestDiagnosis.isPending ? 'Asking Fyn…' : 'Suggest diagnosis'}
        </button>
        {summary.aiSuggestedDiagnosis && (
          <div className="rounded-lg border border-border bg-bg p-3 text-xs space-y-1">
            <p className="text-ink whitespace-pre-wrap">{summary.aiSuggestedDiagnosis}</p>
            {summary.aiSuggestedDiagnosisAt && (
              <p className="text-muted">{formatWhen(summary.aiSuggestedDiagnosisAt)}</p>
            )}
          </div>
        )}
      </section>

      {/* Assignment */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-4">
        <h3 className="text-sm font-semibold text-ink">Assignment</h3>
        <p className="text-xs text-muted">
          Assigned to: <span className="font-mono">{summary.assignedEngineerId ?? '—'}</span>
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => assignToMe.mutate()}
            disabled={busy || resolved}
            className="rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-on-primary hover:bg-primary-dark disabled:opacity-50"
          >
            Assign to me
          </button>
          <input
            value={engineerIdInput}
            onChange={(e) => setEngineerIdInput(e.target.value)}
            placeholder="Engineer id…"
            className="rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
          />
          <button
            type="button"
            onClick={() => assignToEngineer.mutate()}
            disabled={busy || resolved || !engineerIdInput.trim()}
            className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
          >
            Assign
          </button>
          <button
            type="button"
            onClick={() => investigate.mutate()}
            disabled={busy || resolved}
            className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
          >
            Start investigation
          </button>
        </div>

        <div className="space-y-2 border-t border-border pt-4">
          <label className="text-xs text-muted" htmlFor="held-statement-notes">
            Engineer notes -- replaces the whole write-up; the history of what it said before is
            in the timeline below.
          </label>
          <textarea
            id="held-statement-notes"
            value={notesDraft}
            onChange={(e) => setNotesDraft(e.target.value)}
            rows={3}
            className="w-full rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
          />
          <button
            type="button"
            onClick={() => saveNotes.mutate()}
            disabled={saveNotes.isPending}
            className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
          >
            {saveNotes.isPending ? 'Saving…' : 'Save notes'}
          </button>
        </div>

        <div className="space-y-2 border-t border-border pt-4">
          <label className="text-xs text-muted" htmlFor="held-statement-root-cause">Root cause</label>
          <textarea
            id="held-statement-root-cause"
            value={rootCauseDraft}
            onChange={(e) => setRootCauseDraft(e.target.value)}
            rows={2}
            className="w-full rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
          />
          <label className="text-xs text-muted" htmlFor="held-statement-fix-reference">Fix reference</label>
          <input
            id="held-statement-fix-reference"
            value={fixReferenceDraft}
            onChange={(e) => setFixReferenceDraft(e.target.value)}
            placeholder="PR number or URL…"
            className="w-full rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
          />
          <button
            type="button"
            onClick={() => saveFindings.mutate()}
            disabled={saveFindings.isPending}
            className="rounded-lg border border-border px-3 py-1.5 text-xs text-muted hover:text-ink disabled:opacity-50"
          >
            {saveFindings.isPending ? 'Saving…' : 'Save findings'}
          </button>
        </div>
      </section>

      {/* Resolution */}
      <section className="bg-card border border-border rounded-xl2 p-6 space-y-4">
        <h3 className="text-sm font-semibold text-ink">Resolve</h3>
        {resolved && (
          <p className="text-xs text-muted">
            This hold was already {summary.status.replace(/_/g, ' ')}; it cannot be resolved again.
          </p>
        )}
        {!resolved && (
          <p className="text-xs text-muted" data-testid="decision-notice">
            The user is told either way, by push and email unless they have turned these
            notifications off: Approve says their statement is ready, Reject says it wasn&apos;t
            imported (not sent if they have uploaded it again). Notes and reasons stay internal.
          </p>
        )}
        {summary.status === 'REJECTED' && (
          <div className="space-y-2 rounded-lg border border-border p-3" data-testid="reopen-panel">
            <p className="text-xs text-muted">
              Reopen it to read the statement again once its parser fix is live: it goes back to the
              open queue, and the user&apos;s import shows &quot;we&apos;re double-checking this statement&quot; again.
              Re-run the parser before approving.
            </p>
            <div className="flex flex-wrap gap-2">
              <input
                value={reopenReason}
                onChange={(e) => setReopenReason(e.target.value)}
                placeholder="Reason (optional)…"
                className="flex-1 min-w-[16rem] rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink"
              />
              <button
                type="button"
                onClick={() => reopen.mutate()}
                disabled={busy}
                className="rounded-lg border border-border px-3 py-1.5 text-xs font-medium text-ink disabled:opacity-50"
              >
                Reopen
              </button>
            </div>
          </div>
        )}
        <div className="flex flex-wrap gap-2">
          <input
            value={approveNote}
            onChange={(e) => setApproveNote(e.target.value)}
            placeholder="Note (optional)…"
            disabled={resolved}
            className="flex-1 min-w-[16rem] rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink disabled:opacity-50"
          />
          <button
            type="button"
            onClick={() => approve.mutate()}
            disabled={busy || resolved}
            className="rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-on-primary hover:bg-primary-dark disabled:opacity-50"
          >
            Approve
          </button>
        </div>
        <label className="flex items-center gap-2 text-xs text-muted">
          <input
            type="checkbox"
            checked={markFalsePositive}
            onChange={(e) => setMarkFalsePositive(e.target.checked)}
            disabled={resolved}
          />
          Mark as false positive — the trust predicate flagged this, but the extraction was actually fine
        </label>
        <div className="flex flex-wrap gap-2">
          <input
            value={rejectReason}
            onChange={(e) => setRejectReason(e.target.value)}
            placeholder="Internal reason (optional)…"
            disabled={resolved}
            className="flex-1 min-w-[16rem] rounded-lg border border-border bg-bg px-3 py-1.5 text-sm text-ink disabled:opacity-50"
          />
          <button
            type="button"
            onClick={() => reject.mutate()}
            disabled={busy || resolved}
            className="rounded-lg border border-danger text-danger px-3 py-1.5 text-xs font-medium disabled:opacity-50"
          >
            Reject
          </button>
        </div>
      </section>

      {/* Timeline */}
      <section className="bg-card border border-border rounded-xl2 p-6">
        <h3 className="text-sm font-semibold text-ink mb-3">History</h3>
        <ul className="space-y-2">
          {timeline.map((event, index) => (
            <li key={index} className="text-xs border-l-2 border-border pl-3">
              <span className="text-ink font-medium">{EVENT_LABEL[event.eventType] ?? event.eventType}</span>{' '}
              <span className="text-muted">{formatWhen(event.createdAt)}</span>
              {event.notes && <p className="text-muted mt-0.5">{event.notes}</p>}
            </li>
          ))}
          {timeline.length === 0 && <p className="text-muted text-xs">No history yet.</p>}
        </ul>
      </section>
    </div>
  );
}

export default function HeldStatementDetail() {
  const { heldId } = useParams<{ heldId: string }>();
  return (
    <AdminLayout title="Held Statement" subtitle="Trigger evidence, extraction snapshot, and audit history.">
      <RequirePermission permission="TRUST_REVIEW_MANAGE">
        {heldId ? <HeldStatementDetailContent heldId={heldId} /> : <p className="text-muted text-sm">No held statement id.</p>}
      </RequirePermission>
    </AdminLayout>
  );
}
