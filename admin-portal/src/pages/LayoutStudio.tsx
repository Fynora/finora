import { useEffect, useState } from 'react';
import { useQuery, useMutation, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import {
  RefreshCw, FileSearch, AlertTriangle, CheckCircle2, Layers, Ruler, ChevronRight, ChevronDown, Upload,
  HelpCircle,
} from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { EntityDrawer } from '../components/EntityDrawer';
import { Pagination } from '../components/Pagination';
import { PasswordInput } from '../components/PasswordInput';
import { RequirePermission } from '../components/ProtectedRoute';
import { useAdminAuth } from '../context/AdminAuthContext';
import { useNotify } from '../context/NotificationContext';
import { adminStatementAnalysisApi, adminAnalysisRunApi } from '../api/endpoints';
import type {
  StatementAnalysisDto, StatementAnalysisSummaryDto, UnanchoredReasons,
} from '../types';
import { GLOSSARY, describeFailure, describeReason, isPasswordFailure } from './layoutStudioTerms';

/** Rows per page in the analyses table. */
const PAGE_SIZE = 20;
/** Reasons shown before "Show all" -- the list is sorted, so these are the largest. */
const TOP_REASONS = 5;

/**
 * Layout Studio — the workbench over the analysis evidence table.
 *
 * <h2>What this page will not do</h2>
 * Show a number it does not have. Every figure here is read from a stored field; where the
 * workbench is designed to show something that is not recorded yet — parser version, per-section
 * verification findings, approval state — it says so by name instead of rendering a plausible
 * placeholder. A dashboard that displays "0" for "never measured" is worse than one that displays
 * nothing, because the zero looks like a finding and gets acted on.
 *
 * That distinction is not theoretical here: a capability was built, debugged and then deleted
 * because a histogram proved it never fired. The value of that histogram came entirely from it
 * being a real count.
 */

function formatDuration(ms: number | null) {
  if (ms == null) return '—';
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

function formatBytes(bytes: number | null) {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatWhen(iso: string) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

function OutcomeBadge({ outcome, failureCode }: { outcome: string; failureCode: string | null }) {
  const failed = outcome === 'FAILED';
  const failure = failed ? describeFailure(failureCode) : null;
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${
        failed ? 'text-danger bg-danger-bg' : 'text-success bg-success-bg'
      }`}
      title={failure ? `${failure.meaning}${failureCode ? ` (${failureCode})` : ''}` : 'The engine read transactions from this file.'}
    >
      {failed ? <AlertTriangle size={11} /> : <CheckCircle2 size={11} />}
      {failure ? failure.label : 'Read'}
    </span>
  );
}

/**
 * The reason histogram, as bars.
 *
 * The single most useful panel on the page, and the reason it is a histogram rather than a list of
 * reason names: one occurrence in a 2500-line document and two thousand occurrences are the same
 * entry in a set, and only the proportion says whether a missing capability or one odd document is
 * responsible.
 */
function ReasonHistogram({ reasons, emptyLabel }: { reasons: UnanchoredReasons; emptyLabel: string }) {
  const [showAll, setShowAll] = useState(false);
  // Sorted here rather than trusting the payload's key order, because only the top few are shown
  // by default and those have to be the largest.
  const entries = Object.entries(reasons).sort(([, a], [, b]) => b - a);
  if (entries.length === 0) {
    return <p className="text-sm text-muted px-4 py-3">{emptyLabel}</p>;
  }
  const largest = entries[0][1];
  const visible = showAll ? entries : entries.slice(0, TOP_REASONS);
  const hidden = entries.length - visible.length;

  return (
    <>
      <ul className="divide-y divide-border">
        {visible.map(([reason, count], index) => {
          const term = describeReason(reason);
          // Every "date not understood" shape shares one explanation; repeating it on each line
          // only adds height, so it is shown on the first line that needs it.
          const explainedAbove = visible.slice(0, index)
            .some(([earlier]) => describeReason(earlier).meaning === term.meaning);
          return (
            <li key={reason} className="px-4 py-3">
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-sm text-ink font-medium truncate" title={reason}>{term.label}</span>
                <span className="text-sm font-semibold text-ink tabular-nums flex-shrink-0">
                  {count.toLocaleString()}
                  <span className="sr-only"> lines</span>
                </span>
              </div>
              {explainedAbove
                ? <div className="mb-1.5" />
                : <p className="text-xs text-muted mt-0.5 mb-1.5">{term.meaning}</p>}
              <div className="h-1.5 rounded-full bg-bg overflow-hidden" aria-hidden="true">
                <div className="h-full rounded-full bg-primary" style={{ width: `${(count / largest) * 100}%` }} />
              </div>
            </li>
          );
        })}
      </ul>
      {entries.length > TOP_REASONS && (
        <button
          type="button"
          onClick={() => setShowAll((all) => !all)}
          aria-expanded={showAll}
          className="w-full px-4 py-2.5 text-sm font-medium text-primary hover:bg-bg border-t border-border text-left"
        >
          {showAll ? 'Show only the largest reasons' : `Show all ${entries.length} reasons (${hidden} more)`}
        </button>
      )}
    </>
  );
}

function SummaryStrip({ summary }: { summary: StatementAnalysisSummaryDto }) {
  // The last two figures cover a recent window, not all time -- each says so on the tile itself,
  // because a total and a windowed count side by side read as the same kind of number otherwise.
  const recent = `In the last ${summary.analysesInWindow.toLocaleString()} uploads`;
  const cells: { label: string; value: string; hint: string }[] = [
    { label: 'Uploads analysed', value: summary.totalAnalysesEver.toLocaleString(), hint: 'Every statement upload ever tried, by customers or admins.' },
    { label: 'Read', value: summary.parsed.toLocaleString(), hint: 'The engine got transactions out of the file.' },
    { label: 'Failed', value: summary.failed.toLocaleString(), hint: 'The engine stopped. The table below says why.' },
    { label: 'Statement formats', value: summary.distinctLayouts.toLocaleString(), hint: 'Different statement designs seen (by fingerprint).' },
    { label: 'Transactions found', value: summary.rowsExtractedInWindow.toLocaleString(), hint: `${recent}.` },
    {
      label: 'Unmatched lines',
      value: summary.unanchoredRowsInWindow.toLocaleString(),
      hint: `${recent}. Lines not attached to any transaction — not necessarily lost ones.`,
    },
  ];

  return (
    <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-px bg-border border border-border rounded-xl2 overflow-hidden shadow-card">
      {cells.map((cell) => (
        <div key={cell.label} className="bg-card px-4 py-3">
          <p className="text-xs text-muted uppercase tracking-wide">{cell.label}</p>
          <p className="text-lg font-semibold text-ink tabular-nums mt-0.5">{cell.value}</p>
          <p className="text-xs text-muted mt-1 leading-snug">{cell.hint}</p>
        </div>
      ))}
    </div>
  );
}

function AnalysisDetailPanel({ reference }: { reference: string }) {
  const [rawOpen, setRawOpen] = useState(false);
  const { data, isLoading, isError } = useQuery({
    queryKey: ['admin-analysis', reference],
    queryFn: () => adminStatementAnalysisApi.byReference(reference),
  });

  if (isLoading) return <p className="text-sm text-muted">Loading {reference}…</p>;
  if (isError || !data) {
    return (
      <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">
        Couldn&apos;t load {reference} — please try again.
      </p>
    );
  }

  const { analysis, timesLayoutSeen, timesLayoutFailed } = data;
  const failed = analysis.outcome === 'FAILED';
  const failure = failed ? describeFailure(analysis.failureCode) : null;

  return (
    <div className="space-y-4">
      <p className={`text-sm rounded-lg px-3.5 py-2.5 ${failed ? 'text-danger bg-danger-bg' : 'text-success bg-success-bg'}`}>
        {failure
          ? <><strong>{failure.label}.</strong> {failure.meaning}</>
          : <><strong>Read.</strong> The engine got transactions out of this file.</>}
      </p>

      <div className="bg-card border border-border rounded-xl2 shadow-card divide-y divide-border">
        <DetailRow label="Reference" value={analysis.reference} mono />
        <DetailRow
          label="Statement format (fingerprint)"
          value={analysis.layoutFingerprint ?? 'Not identified'}
          mono
          note={analysis.layoutFingerprint ? undefined : 'The file failed before its design could be identified — a PDF with the wrong password never gets that far.'}
        />
        <DetailRow label="File type" value={analysis.sourceFormat ?? '—'} />
        {failed && <DetailRow label="Failure code" value={analysis.failureCode ?? '—'} mono />}
        <DetailRow
          label="Transactions found"
          value={analysis.rowCount == null ? 'Never measured' : analysis.rowCount.toLocaleString()}
          note={analysis.rowCount == null
            ? 'Not the same as zero: the file failed before the engine tried to read transactions.'
            : undefined}
        />
        <DetailRow
          label="Unmatched lines"
          value={analysis.rowCount == null ? 'Never measured' : analysis.unanchoredRowCount.toLocaleString()}
        />
        <DetailRow label="Transaction tables (sections)" value={analysis.sectionCount == null ? '—' : String(analysis.sectionCount)} />
        <DetailRow label="Time taken" value={formatDuration(analysis.durationMs)} />
        <DetailRow label="File size" value={formatBytes(analysis.byteSize)} />
        <DetailRow label="When" value={formatWhen(analysis.createdAt)} />
      </div>

      {analysis.layoutFingerprint && (
        <section aria-labelledby="layout-history-heading" className="bg-card border border-border rounded-xl2 shadow-card">
          <h3 id="layout-history-heading" className="text-sm font-semibold text-ink px-4 pt-3">This layout</h3>
          <p className="text-sm text-muted px-4 pb-3 pt-1">
            This statement format has been uploaded{' '}
            <strong className="text-ink tabular-nums">{timesLayoutSeen.toLocaleString()}</strong>{' '}
            {timesLayoutSeen === 1 ? 'time' : 'times'}, and failed{' '}
            <strong className="text-ink tabular-nums">{timesLayoutFailed.toLocaleString()}</strong>{' '}
            of them. {timesLayoutSeen === 1
              ? 'This is the first time — nothing to compare it against yet.'
              : 'Most uploads failing means the engine cannot read this format; one failure out of many points to one odd file.'}
          </p>
        </section>
      )}

      <section aria-labelledby="diagnostics-heading" className="bg-card border border-border rounded-xl2 shadow-card overflow-hidden">
        <h3 id="diagnostics-heading" className="text-sm font-semibold text-ink px-4 pt-3 pb-1">
          Why lines were not matched
        </h3>
        {/* Same rule as the row count: a file that failed before extraction has an empty
            histogram because nothing was read, not because every line matched. */}
        <ReasonHistogram
          reasons={analysis.unanchoredReasons}
          emptyLabel={analysis.rowCount == null
            ? 'Not measured — the file failed before its lines were read.'
            : 'Every line was matched to a transaction — nothing was left over in this file.'}
        />
      </section>

      <section className="bg-card border border-border rounded-xl2 shadow-card overflow-hidden">
        <button
          type="button"
          onClick={() => setRawOpen((open) => !open)}
          aria-expanded={rawOpen}
          className="w-full flex items-center gap-2 px-4 py-3 text-sm font-medium text-ink hover:bg-bg"
        >
          {rawOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          Raw evidence
        </button>
        {rawOpen && (
          <pre className="px-4 pb-4 text-xs font-mono text-muted overflow-x-auto">
            {JSON.stringify(data, null, 2)}
          </pre>
        )}
      </section>
    </div>
  );
}

function DetailRow({ label, value, mono, note }: { label: string; value: string; mono?: boolean; note?: string }) {
  return (
    <div className="px-4 py-2.5">
      <div className="flex items-center gap-3">
        <span className="text-sm text-muted flex-1">{label}</span>
        <span className={`text-sm text-ink text-right truncate max-w-[60%] ${mono ? 'font-mono' : ''}`} title={value}>{value}</span>
      </div>
      {note && <p className="text-xs text-muted mt-1">{note}</p>}
    </div>
  );
}

function AnalysisTable({
  analyses, selected, onSelect,
}: {
  analyses: StatementAnalysisDto[];
  selected: string | null;
  onSelect: (reference: string) => void;
}) {
  if (analyses.length === 0) {
    return (
      <p className="text-sm text-muted bg-card border border-border rounded-xl2 px-4 py-6 text-center">
        No statements have been analysed yet. Every upload — successful or not — appears here.
      </p>
    );
  }

  return (
    // contain:inline-size keeps the table's own width from counting toward the page's: without it
    // the wide, no-wrap table stretched the whole admin layout (page 1431px wide in a 1009px window,
    // measured) instead of scrolling inside this box.
    <div className="bg-card border border-border rounded-xl2 shadow-card overflow-x-auto [contain:inline-size]">
      <table className="w-full text-sm">
        <caption className="sr-only">Statement uploads, newest first</caption>
        <thead>
          <tr className="text-left text-xs text-muted uppercase tracking-wide border-b border-border">
            <th scope="col" className="px-4 py-2.5 font-medium">Reference</th>
            <th scope="col" className="px-4 py-2.5 font-medium">Statement format</th>
            <th scope="col" className="px-4 py-2.5 font-medium">Result</th>
            <th scope="col" className="px-4 py-2.5 font-medium text-right">Transactions</th>
            <th scope="col" className="px-4 py-2.5 font-medium text-right">Unmatched lines</th>
            <th scope="col" className="px-4 py-2.5 font-medium text-right">Time taken</th>
            <th scope="col" className="px-4 py-2.5 font-medium">When</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {analyses.map((analysis) => (
            <tr
              key={analysis.reference}
              className={selected === analysis.reference ? 'bg-bg' : undefined}
            >
              <th scope="row" className="px-4 py-2.5 font-normal text-left">
                <button
                  type="button"
                  onClick={() => onSelect(analysis.reference)}
                  aria-current={selected === analysis.reference ? 'true' : undefined}
                  className="font-mono text-primary hover:underline whitespace-nowrap"
                >
                  {analysis.reference}
                </button>
              </th>
              <td className="px-4 py-2.5 font-mono text-muted whitespace-nowrap" title={analysis.layoutFingerprint ? undefined : 'Not identified — the file failed before its design could be read.'}>
                {analysis.layoutFingerprint ?? '—'}
              </td>
              <td className="px-4 py-2.5">
                <OutcomeBadge outcome={analysis.outcome} failureCode={analysis.failureCode} />
              </td>
              {/* "Never measured" is rendered as an em dash, never as 0 -- a document that failed
                  before extraction and one that extracted nothing lead to different investigations. */}
              <td className="px-4 py-2.5 text-right tabular-nums text-ink">
                {analysis.rowCount == null ? '—' : analysis.rowCount.toLocaleString()}
              </td>
              <td className="px-4 py-2.5 text-right tabular-nums text-muted">
                {analysis.rowCount == null ? '—' : analysis.unanchoredRowCount.toLocaleString()}
              </td>
              <td className="px-4 py-2.5 text-right tabular-nums text-muted">{formatDuration(analysis.durationMs)}</td>
              <td className="px-4 py-2.5 text-muted whitespace-nowrap">{formatWhen(analysis.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Every term the page uses, in plain words. Collapsed by default so it costs one line of height
 * for someone who already knows them.
 */
function Glossary() {
  const [open, setOpen] = useState(false);
  return (
    <section className="bg-card border border-border rounded-xl2 shadow-card overflow-hidden">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-controls="layout-studio-glossary"
        className="w-full flex items-center gap-2 px-4 py-3 text-sm font-medium text-ink hover:bg-bg text-left"
      >
        <HelpCircle size={14} className="text-primary" />
        <span className="flex-1">What do these terms mean?</span>
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
      </button>
      {open && (
        <dl id="layout-studio-glossary" className="divide-y divide-border border-t border-border">
          {GLOSSARY.map((term) => (
            <div key={term.label} className="px-4 py-2.5">
              <dt className="text-sm font-medium text-ink">{term.label}</dt>
              <dd className="text-sm text-muted mt-0.5">{term.meaning}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

/**
 * What the workbench is designed to show and cannot yet.
 *
 * Named explicitly rather than mocked up, because a placeholder that looks like data is the one
 * failure mode this page exists to avoid. Each line is a field nothing writes today.
 */
function NotYetRecorded() {
  const pending = [
    ['Parser version', 'No parse records which build produced it, so a before/after timeline across engine versions cannot be drawn.'],
    ['Verification findings', 'Balance chain, statement totals and summary totals are checked during import but the per-check results are not persisted.'],
    ['Per-section rows', 'Only the section COUNT is stored, so a two-section statement cannot show which section produced which rows.'],
    ['Largest cell', 'Not captured anywhere; it was measured with a temporary probe.'],
    ['Approval state', 'Approving a layout creates knowledge, which is a separate curated table that does not exist yet.'],
  ];

  return (
    <section aria-labelledby="pending-heading" className="bg-card border border-border rounded-xl2 shadow-card">
      <div className="flex items-center gap-2 px-4 pt-3">
        <Ruler size={14} className="text-muted" />
        <h2 id="pending-heading" className="text-sm font-semibold text-ink">Not recorded yet</h2>
      </div>
      <p className="text-sm text-muted px-4 pt-1">
        Listed by name rather than shown as empty tiles — a placeholder that looks like a
        measurement is worse than an absent one, because it gets acted on.
      </p>
      <dl className="divide-y divide-border mt-2">
        {pending.map(([field, why]) => (
          <div key={field} className="px-4 py-2.5">
            <dt className="text-sm text-ink">{field}</dt>
            <dd className="text-sm text-muted">{why}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

/**
 * Run the engine on a document without importing it.
 *
 * <p>The change that turns this page from a viewer into a workbench: until now every analysis in
 * the table arrived because a real customer uploaded something. An engineer studying a layout had
 * to find a statement, write a probe and read a console.
 *
 * <p>Nothing is imported and nothing is stored — not the file, not the transactions, not even the
 * merchants the parser resolves on the way through. See AdminAnalysisService for how that is
 * enforced, which is less obvious than it looks.
 */
function AnalysisUploadPanel({ onAnalysed }: { onAnalysed: (reference: string) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState('');
  const [needsPassword, setNeedsPassword] = useState(false);
  const queryClient = useQueryClient();
  const notify = useNotify();

  const run = useMutation({
    mutationFn: () => adminAnalysisRunApi.analyze(file as File, password || undefined),
    onSuccess: (detail) => {
      const { outcome, failureCode, reference } = detail.analysis;

      // A failed parse is a successful analysis -- the backend returns 200 with a FAILED row on
      // purpose, so the admin gets the evidence link precisely when the engine could not read the
      // document. The one failure worth interrupting for is a password, because that one the
      // admin can actually fix and retry.
      // The API returns the stored ErrorCode NAME (IMPORT_PDF_PASSWORD_REQUIRED), not the wire
      // code (IMPORT_008); isPasswordFailure accepts both. Matching only the wire code meant this
      // prompt never appeared for a real encrypted upload.
      if (outcome === 'FAILED' && isPasswordFailure(failureCode)) {
        setNeedsPassword(true);
        notify.error('This document is encrypted. Enter its password and analyse again.');
      } else if (outcome === 'FAILED') {
        notify.error(`Analysed — the engine could not read this document: ${describeFailure(failureCode).label}.`);
      } else {
        notify.success(`Analysed as ${reference}.`);
        setNeedsPassword(false);
      }

      setPassword('');
      void queryClient.invalidateQueries({ queryKey: ['admin-analyses'] });
      void queryClient.invalidateQueries({ queryKey: ['admin-analyses-summary'] });
      onAnalysed(reference);
    },
    onError: () => notify.error("Couldn't analyse that document — please try again."),
  });

  return (
    <section aria-labelledby="upload-heading" className="bg-card border border-border rounded-xl2 shadow-card p-4">
      <div className="flex items-center gap-2">
        <Upload size={14} className="text-primary" />
        <h2 id="upload-heading" className="text-sm font-semibold text-ink">Analyse a statement</h2>
      </div>
      <p className="text-sm text-muted mt-1">
        Runs the real engine and imports nothing — no account, no transactions, and the file itself
        is not stored. Leaves a permanent analysis you can link to.
      </p>

      <div className="flex flex-wrap items-end gap-3 mt-3">
        <div>
          <label htmlFor="analysis-file" className="block text-xs text-muted mb-1">Statement (PDF or CSV)</label>
          <input
            id="analysis-file"
            type="file"
            accept=".pdf,.csv"
            onChange={(event) => {
              setFile(event.target.files?.[0] ?? null);
              setNeedsPassword(false);
            }}
            className="text-sm text-ink file:mr-3 file:rounded-lg file:border file:border-border file:bg-bg file:px-3 file:py-1.5 file:text-sm file:text-ink"
          />
        </div>

        {needsPassword && (
          <div>
            <label htmlFor="analysis-password" className="block text-xs text-muted mb-1">Document password</label>
            <PasswordInput
              id="analysis-password"
              value={password}
              onChange={setPassword}
              autoComplete="off"
              className="text-sm text-ink border border-border rounded-lg px-3 py-1.5 pr-10 bg-card"
            />
          </div>
        )}

        <button
          type="button"
          onClick={() => run.mutate()}
          disabled={!file || run.isPending}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-on-primary bg-primary rounded-lg px-3.5 py-2 disabled:opacity-50"
        >
          {run.isPending ? <RefreshCw size={14} className="animate-spin" /> : <Upload size={14} />}
          {run.isPending ? 'Analysing…' : 'Analyse'}
        </button>
      </div>
    </section>
  );
}

function LayoutStudioContent() {
  const [selected, setSelected] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  // The moment the list is frozen at: the newest row's createdAt, taken from the first page.
  // Every page is then read "as of" it, so an upload arriving mid-browse cannot push a row from
  // page 1 onto page 2 and show it twice. Null means "not frozen yet -- show the newest".
  const [snapshot, setSnapshot] = useState<string | null>(null);
  const { hasPermission } = useAdminAuth();
  const queryClient = useQueryClient();

  const summary = useQuery({
    queryKey: ['admin-analyses-summary'],
    queryFn: () => adminStatementAnalysisApi.summary(),
  });
  const analyses = useQuery({
    queryKey: ['admin-analyses', page, snapshot],
    queryFn: () => adminStatementAnalysisApi.paged(page, PAGE_SIZE, snapshot ?? undefined),
    // A frozen page cannot change -- analysis rows are never edited after they are written (the
    // only later write, a deleted user's anonymisation, touches no field shown here) -- so it is
    // not refetched until Refresh or a new analysis unfreezes the list.
    staleTime: snapshot ? Infinity : 0,
    // Keeps the current page on screen while the next one loads, instead of flashing "Loading…"
    // and collapsing the page height on every click.
    placeholderData: keepPreviousData,
  });

  const isFetching = summary.isFetching || analyses.isFetching;

  // Freeze the list at the first page's newest row. The unfrozen first page is, by definition,
  // the same rows as the first page frozen at its own newest row, so it is copied into that
  // cache entry instead of being fetched a second time.
  const firstRowAt = snapshot === null && page === 0 && !analyses.isPlaceholderData
    ? analyses.data?.content[0]?.createdAt ?? null
    : null;
  useEffect(() => {
    if (firstRowAt === null || !analyses.data) return;
    queryClient.setQueryData(['admin-analyses', 0, firstRowAt], analyses.data);
    setSnapshot(firstRowAt);
  }, [firstRowAt, analyses.data, queryClient]);

  /** Back to the newest uploads: unfreeze and start again from the first page. */
  function showNewest() {
    // Dropped, not just invalidated: a cached unfrozen first page would be shown at once and the
    // list would re-freeze at its OLD newest row, hiding exactly the uploads Refresh is for.
    queryClient.removeQueries({ queryKey: ['admin-analyses', 0, null], exact: true });
    setSnapshot(null);
    setPage(0);
  }

  function refetchAll() {
    void summary.refetch();
    if (snapshot === null && page === 0) void analyses.refetch();
    else showNewest();
  }

  function showAnalysed(reference: string) {
    // A new analysis is newer than any snapshot, so the list is unfrozen to include it.
    showNewest();
    setSelected(reference);
  }

  if (summary.isLoading) return <p className="text-muted text-sm">Loading…</p>;
  if (summary.isError || !summary.data) {
    return (
      <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">
        Couldn&apos;t load statement analyses — please try again later.
      </p>
    );
  }

  const pageData = analyses.data;

  return (
    <div className="space-y-6">
      <div className="flex justify-end">
        <button
          type="button"
          onClick={refetchAll}
          disabled={isFetching}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-ink border border-border rounded-lg px-3.5 py-2 hover:bg-bg disabled:opacity-50"
        >
          <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      <Glossary />

      {hasPermission('ENGINE_ANALYSIS_RUN') && <AnalysisUploadPanel onAnalysed={showAnalysed} />}

      <SummaryStrip summary={summary.data} />

      <section aria-labelledby="analyses-heading" className="space-y-3">
        <div className="flex items-center gap-2">
          <FileSearch size={16} className="text-primary" />
          <h2 id="analyses-heading" className="text-sm font-semibold text-muted uppercase tracking-wide">
            All uploads
          </h2>
          <span className="text-xs text-muted">
            newest first · click a reference for details
            {snapshot && (
              <> · showing uploads up to {formatWhen(snapshot)}, press Refresh for newer ones</>
            )}
          </span>
        </div>
        {/* A failed page is reported here, in the table's place, rather than replacing the whole
            screen: the summary and the upload panel above are still valid and still usable. */}
        {analyses.isError ? (
          <div className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5 flex items-center justify-between gap-3">
            <span>Couldn&apos;t load statement analyses for page {page + 1}.</span>
            <button
              type="button"
              onClick={() => void analyses.refetch()}
              className="text-sm font-medium underline flex-shrink-0"
            >
              Try again
            </button>
          </div>
        ) : !pageData ? (
          <p className="text-muted text-sm">Loading…</p>
        ) : (
          <>
            {/* Dimmed while the previous page stands in for the one being loaded, so the rows on
                screen are never mistaken for the page the pager already names. */}
            <div
              aria-busy={analyses.isPlaceholderData}
              className={analyses.isPlaceholderData ? 'opacity-50 transition-opacity' : 'transition-opacity'}
            >
              <AnalysisTable analyses={pageData.content} selected={selected} onSelect={setSelected} />
            </div>
            {/* The requested page, not pageData.page: while the next page loads, placeholderData
                still holds the previous response, so a second quick click would re-request the
                same page. */}
            <Pagination
              page={page}
              totalPages={pageData.totalPages}
              totalElements={pageData.totalElements}
              pageSize={pageData.size}
              onPageChange={setPage}
            />
          </>
        )}
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <section aria-labelledby="engine-reasons-heading" className="bg-card border border-border rounded-xl2 shadow-card overflow-hidden">
          <div className="flex items-center gap-2 px-4 pt-3">
            <Layers size={14} className="text-primary" />
            <h2 id="engine-reasons-heading" className="text-sm font-semibold text-ink">
              Why lines were not matched, in the last {summary.data.analysesInWindow.toLocaleString()} uploads
            </h2>
          </div>
          <p className="text-sm text-muted px-4 pt-1 pb-2">
            A reason that is large across many uploads points to something the engine cannot do yet.
            The same reason in only one upload points to that one file.
          </p>
          <ReasonHistogram
            reasons={summary.data.unanchoredReasons}
            emptyLabel="Every line was matched to a transaction in every analysed upload."
          />
        </section>

        <NotYetRecorded />
      </div>

      <EntityDrawer
        open={selected !== null}
        onClose={() => setSelected(null)}
        title={selected ?? ''}
        subtitle="One upload attempt"
        tabs={[{
          id: 'summary',
          label: 'Summary',
          content: selected ? <AnalysisDetailPanel reference={selected} /> : null,
        }]}
      />
    </div>
  );
}

export default function LayoutStudio() {
  return (
    <AdminLayout
      title="Layout Studio"
      subtitle="Every upload attempt, successful or not -- what the engine read, and why it could not read the rest"
    >
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <LayoutStudioContent />
      </RequirePermission>
    </AdminLayout>
  );
}
