import { useEffect, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Info } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import {
  adminActivationApi, type ActivationLandingPathRow, type ActivationMomentName, type ActivationReached,
  type ActivationReport, type ActivationWeekRow, type FunnelStep, type ReachedStep,
} from '../api/endpoints';
import {
  MIN_SIGNUPS_TO_JUDGE, importRate, sortRates, tooSmall, type RateRow, type RateSort, type RateSortKey,
} from '../lib/activationRates';
import {
  ANSWER_LABELS, DELETION_REASON_LABELS, FOCUS_LABELS, MOMENT_QUESTIONS, NO_ANSWER, NO_ANSWER_LABEL,
  SOURCE_LABELS, SPENDING_TRACKING_LABELS, STEP_LABELS, STEP_ORDER, formatDays, formatIsoDate, formatIsoMonth,
} from '../lib/activationLabels';
import {
  DEFAULT_WEEKS, FILE_READ_DROP_STEPS, REACHED_LABELS, REACHED_ORDER, biggestDrop, percent, verdict, weekLines,
} from '../lib/activationFunnel';

const DEFAULT_RANGE_DAYS = 90;

/** A YYYY-MM-DD for the admin's own calendar day, not UTC's. */
function isoDay(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function defaultRange(): { from: string; to: string } {
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - DEFAULT_RANGE_DAYS);
  return { from: isoDay(from), to: isoDay(to) };
}

const MOMENTS: ActivationMomentName[] = ['NO_FILE', 'REFUSED', 'NOT_CONFIRMED'];

const th = 'text-left text-xs font-semibold text-muted uppercase tracking-wide px-3 py-2 border-b border-border';
const td = 'px-3 py-2 border-b border-border text-sm text-ink';

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">{title}</h2>
      <div className="bg-card border border-border rounded-xl2 shadow-card overflow-x-auto">{children}</div>
    </section>
  );
}

type FunnelView = 'reached' | 'stopped';

/** The verdict: where signups get stuck, the activation rate and who came back, in one line. */
function Verdict({ report }: { report: ActivationReport }) {
  if (!report.reached) return null;
  return (
    <p aria-label="Verdict" className="text-base font-medium text-ink bg-card border border-border rounded-xl2 shadow-card p-4">
      {verdict(report.reached, report.secondImport.cameBackLaterDay)}
    </p>
  );
}

/** "Refused 1 · Read failed 2": where the people who chose a file but never had one read stopped. */
function fileReadSplit(reached: ActivationReached): string {
  return FILE_READ_DROP_STEPS.filter((s) => (reached.stoppedAt[s] ?? 0) > 0)
    .map((s) => `${s === 'CHOSE_FILE' ? 'No result' : STEP_LABELS[s]} ${reached.stoppedAt[s]}`)
    .join(' · ');
}

/**
 * Each main-path step as a bar: how many of every counted signup reached it, and how many the step
 * before lost. The biggest drop is marked. Between a chosen file and a read one, the drop is split
 * into where those people stopped.
 */
function ReachedBars({ reached }: { reached: ActivationReached }) {
  if (reached.signups === 0) return <p className="p-4 text-sm text-muted">No signups to count yet.</p>;
  const biggest = biggestDrop(reached)?.row.step;
  return (
    <ol aria-label="Steps reached" className="p-4 space-y-3">
      {reached.steps.map((row, i) => {
        const previous = i === 0 ? null : reached.steps[i - 1];
        const split = row.step === 'FILE_READ' ? fileReadSplit(reached) : '';
        return (
          <li key={row.step} aria-label={REACHED_LABELS[row.step]} className="grid grid-cols-[8rem_1fr_6rem] gap-x-3 gap-y-1 items-center">
            <span className="text-sm text-ink">{REACHED_LABELS[row.step]}</span>
            <span className="h-5 bg-surface border border-border rounded" aria-hidden="true">
              <span className="block h-full bg-primary rounded" style={{ width: `${(row.reached / reached.signups) * 100}%` }} />
            </span>
            <span className="text-sm text-ink tabular-nums text-right">
              {row.reached} <span className="text-muted">({percent(row.reached, reached.signups)})</span>
            </span>
            {previous && row.lost > 0 && (
              <span className={`col-start-2 col-span-2 text-xs tabular-nums ${row.step === biggest ? 'text-danger font-semibold' : 'text-muted'}`}>
                −{row.lost} of {previous.reached} ({percent(row.lost, previous.reached)})
                {row.step === biggest && ' · biggest drop'}
                {row.lostBeforeFunnelRecord > 0 && ` · ${row.lostBeforeFunnelRecord} signed up before this step was recorded`}
                {split && ` · ${split}`}
              </span>
            )}
          </li>
        );
      })}
    </ol>
  );
}

function sumOf(rows: ActivationWeekRow[], pick: (row: ActivationWeekRow) => number | undefined): number {
  return rows.reduce((sum, row) => sum + (pick(row) ?? 0), 0);
}

/**
 * Signups per IST week, as main-path steps reached or at their furthest step, with how many came back
 * another day. The last weeks by default; a run of weeks with no signups is one line; a total row
 * sums the weeks shown. A cell is a count and links nowhere.
 */
function WeeklyTable({ weeks, view, showAll }: { weeks: ActivationWeekRow[]; view: FunnelView; showAll: boolean }) {
  if (weeks.length === 0) return <p className="p-4 text-sm text-muted">No signups to count yet.</p>;
  const { lines, shown } = weekLines(weeks, showAll);
  const columns: { key: string; label: string; value: (row: ActivationWeekRow) => number | undefined }[] = [
    ...(view === 'reached'
      ? REACHED_ORDER.map((step: ReachedStep) => ({
        key: step, label: REACHED_LABELS[step], value: (r: ActivationWeekRow) => r.reached?.[step],
      }))
      : STEP_ORDER.map((step: FunnelStep) => ({
        key: step, label: STEP_LABELS[step], value: (r: ActivationWeekRow) => r.counts[step] ?? 0,
      }))),
    { key: 'cameBack', label: 'Came back another day', value: (r) => r.cameBackLaterDay },
  ];
  const cell = (n: number | undefined) => (n === undefined ? '—' : String(n));
  return (
    <table aria-label={view === 'reached' ? 'Steps reached by signup week' : 'Furthest step by signup week'} className="w-full">
      <thead>
        <tr>
          <th className={th}>Signup week</th>
          {columns.map((c) => <th key={c.key} className={th}>{c.label}</th>)}
        </tr>
      </thead>
      <tbody>
        {lines.map((line) => (line.kind === 'week' ? (
          <tr key={line.row.weekStart}>
            <td className={td}>{formatIsoDate(line.row.weekStart)}</td>
            {columns.map((c) => <td key={c.key} className={`${td} tabular-nums`}>{cell(c.value(line.row))}</td>)}
          </tr>
        ) : (
          <tr key={line.from}>
            <td className={`${td} text-muted`}>
              {line.weeks === 1 ? formatIsoDate(line.from) : `${formatIsoDate(line.from)} – ${formatIsoDate(line.to)}`}
            </td>
            <td colSpan={columns.length} className={`${td} text-muted`}>
              {line.weeks === 1 ? 'No signups' : `No signups in ${line.weeks} weeks`}
            </td>
          </tr>
        )))}
      </tbody>
      <tfoot>
        <tr>
          <th scope="row" className={`${td} text-left font-semibold`}>Total</th>
          {columns.map((c) => {
            const missing = shown.some((r) => c.value(r) === undefined);
            return <td key={c.key} className={`${td} tabular-nums font-semibold`}>{missing ? '—' : sumOf(shown, c.value)}</td>;
          })}
        </tr>
      </tfoot>
    </table>
  );
}

/** The funnel: the reached view by default (bars, then by week); the stopped-at table one click away. */
function Funnel({ report }: { report: ActivationReport }) {
  const [view, setView] = useState<FunnelView>('reached');
  const [showAll, setShowAll] = useState(false);
  // A backend from before the reached counts sends only the stopped-at ones.
  const current: FunnelView = report.reached ? view : 'stopped';
  const hidden = report.weeks.length - DEFAULT_WEEKS;
  const toggle = (active: boolean) =>
    `px-3 py-1 text-sm rounded-lg border ${active ? 'bg-primary text-on-primary border-primary' : 'bg-card text-ink border-border'}`;
  return (
    <section>
      <div className="flex flex-wrap items-center gap-2 mb-3">
        <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mr-auto">Funnel</h2>
        {report.reached && (
          <div role="group" aria-label="Funnel view" className="flex gap-1">
            <button type="button" aria-pressed={current === 'reached'} className={toggle(current === 'reached')} onClick={() => setView('reached')}>
              Reached
            </button>
            <button type="button" aria-pressed={current === 'stopped'} className={toggle(current === 'stopped')} onClick={() => setView('stopped')}>
              Stopped at
            </button>
          </div>
        )}
      </div>
      <div className="space-y-4">
        {current === 'reached' && report.reached && (
          <div className="bg-card border border-border rounded-xl2 shadow-card">
            <ReachedBars reached={report.reached} />
          </div>
        )}
        <div className="bg-card border border-border rounded-xl2 shadow-card overflow-x-auto">
          <WeeklyTable weeks={report.weeks} view={current} showAll={showAll} />
        </div>
        {hidden > 0 && (
          <button type="button" className="text-sm text-primary" aria-expanded={showAll} onClick={() => setShowAll((v) => !v)}>
            {showAll ? `Show the last ${DEFAULT_WEEKS} weeks only` : `Show all ${report.weeks.length} weeks`}
          </button>
        )}
      </div>
    </section>
  );
}

function stepTotal(counts: Record<FunnelStep, number>): number {
  return STEP_ORDER.reduce((sum, step) => sum + (counts[step] ?? 0), 0);
}

/** The headline counts over every counted signup, with the second-import measure beside them. */
function Totals({ report }: { report: ActivationReport }) {
  const signups = report.weeks.reduce((sum, w) => sum + stepTotal(w.counts), 0);
  const gotDataIn = report.weeks.reduce((sum, w) => sum + (w.counts.GOT_DATA_IN ?? 0), 0);
  const items = [
    { label: 'Signups counted', value: String(signups) },
    { label: 'Got data in', value: String(gotDataIn) },
    { label: 'Second import', value: String(report.secondImport.users) },
    { label: 'Median days, first to second import', value: formatDays(report.secondImport.medianDays) },
    { label: 'Came back another day', value: String(report.secondImport.cameBackLaterDay) },
  ];
  return (
    <dl aria-label="Totals" className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
      {items.map((i) => (
        <div key={i.label} className="bg-card border border-border rounded-xl2 shadow-card p-4">
          <dt className="text-xs font-semibold text-muted uppercase tracking-wide">{i.label}</dt>
          <dd className="mt-1 text-xl font-semibold text-ink tabular-nums">{i.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * Signups per source at their furthest step, with how many sent a second, different statement and
 * how many came back on a later day.
 */
function SourceTable({ rows }: { rows: ActivationReport['bySource'] }) {
  return (
    <table aria-label="Funnel by signup source" className="w-full">
      <thead>
        <tr>
          <th className={th}>Source</th>
          {STEP_ORDER.map((step) => <th key={step} className={th}>{STEP_LABELS[step]}</th>)}
          <th className={th}>Second import</th>
          <th className={th}>Median days to second</th>
          <th className={th}>Came back another day</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.kind === 'UTM' ? `UTM:${r.utmSource}` : r.kind}>
            <td className={td}>{r.kind === 'UTM' ? `utm_source: ${r.utmSource}` : SOURCE_LABELS[r.kind]}</td>
            {STEP_ORDER.map((step) => <td key={step} className={`${td} tabular-nums`}>{r.counts[step] ?? 0}</td>)}
            <td className={`${td} tabular-nums`}>{r.secondImport.users}</td>
            <td className={`${td} tabular-nums`}>{formatDays(r.secondImport.medianDays)}</td>
            <td className={`${td} tabular-nums`}>{r.secondImport.cameBackLaterDay}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * V300: sign-ups by the first public page of the visit, with how many got data in. Counts only, for
 * accounts made after landing pages began to be recorded. The busiest pages are named; the rest are
 * summed, and accounts with no landing page recorded are their own line.
 */
function LandingPathTable({ landing }: { landing: NonNullable<ActivationReport['landingPaths']> }) {
  const toRow = (key: string, label: string, row: ActivationLandingPathRow, pinned = false): RateRow => ({
    key, label, signups: row.signups, gotDataIn: row.gotDataIn, cameBackLaterDay: row.cameBackLaterDay ?? null, pinned,
  });
  const rows = [
    ...landing.pages.map((p) => toRow(p.path ?? '', p.path ?? '', p)),
    ...(landing.other.signups > 0 ? [toRow('(other)', 'Other pages', landing.other, true)] : []),
    ...(landing.none.signups > 0 ? [toRow('(none)', 'No landing page recorded', landing.none, true)] : []),
  ];
  if (rows.length === 0) {
    return <p className="p-4 text-sm text-muted">No signups since landing pages began to be recorded.</p>;
  }
  return <RateTable label="Signups by landing page" firstColumn="Landing page" rows={rows} />;
}

const RATE_COLUMNS: { key: RateSortKey; label: string }[] = [
  { key: 'signups', label: 'Signed up' },
  { key: 'gotDataIn', label: 'Got data in' },
  { key: 'rate', label: 'Import rate' },
  { key: 'cameBackLaterDay', label: 'Came back another day' },
];

/**
 * Signups, how many got data in, the import rate and who came back another day, one row per
 * source or page. Every count column sorts; a row with fewer than MIN_SIGNUPS_TO_JUDGE signups is
 * marked too small to judge, and sorts below the rest by rate.
 */
function RateTable({ label, firstColumn, rows, extraHeaders = [] }: {
  label: string; firstColumn: string; rows: (RateRow & { extra?: string[] })[]; extraHeaders?: string[];
}) {
  const [sort, setSort] = useState<RateSort>({ key: 'signups', descending: true });
  const sorted = sortRates(rows, sort) as (RateRow & { extra?: string[] })[];
  const toggle = (key: RateSortKey) =>
    setSort((s) => (s.key === key ? { key, descending: !s.descending } : { key, descending: true }));
  return (
    <table aria-label={label} className="w-full">
      <thead>
        <tr>
          <th className={th}>{firstColumn}</th>
          {RATE_COLUMNS.map((c) => (
            <th key={c.key} className={th} aria-sort={sort.key === c.key ? (sort.descending ? 'descending' : 'ascending') : 'none'}>
              <button type="button" className="uppercase tracking-wide font-semibold hover:text-ink" onClick={() => toggle(c.key)}>
                {c.label}
                {sort.key === c.key && <span aria-hidden="true">{sort.descending ? ' ↓' : ' ↑'}</span>}
              </button>
            </th>
          ))}
          {extraHeaders.map((h) => <th key={h} className={th}>{h}</th>)}
        </tr>
      </thead>
      <tbody>
        {sorted.map((r) => {
          const rate = importRate(r);
          const small = tooSmall(r);
          return (
            <tr key={r.key}>
              <td className={td}>{r.label}</td>
              <td className={`${td} tabular-nums`}>{r.signups}</td>
              <td className={`${td} tabular-nums`}>{r.gotDataIn}</td>
              <td className={`${td} tabular-nums ${small ? 'text-muted' : ''}`}>
                {rate === null ? '—' : `${Math.round(rate * 100)}%`}
                {small && <span className="ml-2 text-xs text-warning">too few to judge</span>}
              </td>
              <td className={`${td} tabular-nums`}>{r.cameBackLaterDay ?? '—'}</td>
              {(r.extra ?? []).map((cell, i) => <td key={extraHeaders[i]} className={`${td} tabular-nums`}>{cell}</td>)}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/** Each source with its import rate; the steps each source stopped at are one click away. */
function SourceRates({ rows }: { rows: ActivationReport['bySource'] }) {
  const [showSteps, setShowSteps] = useState(false);
  const rateRows = rows.map((r) => ({
    key: r.kind === 'UTM' ? `UTM:${r.utmSource}` : r.kind,
    label: r.kind === 'UTM' ? `utm_source: ${r.utmSource}` : SOURCE_LABELS[r.kind],
    signups: stepTotal(r.counts),
    gotDataIn: r.counts.GOT_DATA_IN ?? 0,
    cameBackLaterDay: r.secondImport.cameBackLaterDay,
    extra: [String(r.secondImport.users), formatDays(r.secondImport.medianDays)],
  }));
  return (
    <section>
      <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">Signups by source</h2>
      <div className="bg-card border border-border rounded-xl2 shadow-card overflow-x-auto">
        <RateTable label="Signups by source" firstColumn="Source" rows={rateRows}
          extraHeaders={['Second import', 'Median days to second']} />
      </div>
      <button type="button" className="mt-2 text-sm text-muted hover:text-ink" aria-expanded={showSteps}
        onClick={() => setShowSteps((v) => !v)}>
        {showSteps ? 'Hide where each source stopped' : 'Show where each source stopped'}
      </button>
      {showSteps && (
        <div className="mt-3 bg-card border border-border rounded-xl2 shadow-card overflow-x-auto">
          <SourceTable rows={rows} />
        </div>
      )}
    </section>
  );
}

/** An ISO instant as its date in IST, the zone the report files days under. */
function istDate(instant: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(instant));
  return formatIsoDate(parts);
}

/** Spec §6.2: each moment's answers and skips in the chosen range, zeros included. */
function AnswersByMoment({ answers }: { answers: ActivationReport['answers'] }) {
  return (
    <div className="divide-y divide-border">
      {MOMENTS.map((moment) => (
        <div key={moment} className="p-4">
          <p className="text-sm font-medium text-ink mb-2">{MOMENT_QUESTIONS[moment]}</p>
          <table aria-label={MOMENT_QUESTIONS[moment]} className="w-full max-w-lg">
            <tbody>
              {answers.filter((a) => a.moment === moment).map((a) => (
                <tr key={a.answer}>
                  <td className="py-1 text-sm text-ink">{ANSWER_LABELS[a.answer]}</td>
                  <td className="py-1 text-sm text-muted tabular-nums text-right">{a.count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  );
}

function joinCounts(items: { label: string; count: number }[]): string {
  return items.length === 0 ? '—' : items.map((i) => `${i.label}: ${i.count}`).join(', ');
}

/** Spec §6.3: for each furthest step, what those people answered and told us at setup. */
function ByStep({ report }: { report: ActivationReport }) {
  const labelOf = (map: Record<string, string>, key: string) => (key === NO_ANSWER ? NO_ANSWER_LABEL : map[key] ?? key);
  const rows = STEP_ORDER.map((step: FunnelStep) => ({
    step,
    answers: report.answersByStep.filter((a) => a.step === step)
      .map((a) => ({ label: `${MOMENT_QUESTIONS[a.moment]} ${ANSWER_LABELS[a.answer]}`, count: a.count })),
    focus: report.focusByStep.filter((f) => f.step === step)
      .map((f) => ({ label: labelOf(FOCUS_LABELS, f.label), count: f.count })),
    tracking: report.trackingByStep.filter((t) => t.step === step)
      .map((t) => ({ label: labelOf(SPENDING_TRACKING_LABELS, t.label), count: t.count })),
  }));
  return (
    <table aria-label="Answers, focus and tracking by step" className="w-full">
      <thead>
        <tr>
          <th className={th}>Furthest step</th>
          <th className={th}>Answers in range</th>
          <th className={th}>Focus at setup</th>
          <th className={th}>How they tracked spending</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.step}>
            <td className={td}>{STEP_LABELS[r.step]}</td>
            <td className={`${td} text-muted`}>{joinCounts(r.answers)}</td>
            <td className={`${td} text-muted`}>{joinCounts(r.focus)}</td>
            <td className={`${td} text-muted`}>{joinCounts(r.tracking)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Spec §6.4: a count per reason per month. */
function DeletionReasons({ reasons }: { reasons: ActivationReport['deletionReasons'] }) {
  if (reasons.length === 0) return <p className="p-4 text-sm text-muted">No deletion reasons recorded yet.</p>;
  return (
    <table aria-label="Deletion reasons by month" className="w-full">
      <thead>
        <tr>
          <th className={th}>Month</th>
          <th className={th}>Reason</th>
          <th className={th}>Accounts</th>
        </tr>
      </thead>
      <tbody>
        {reasons.map((r) => (
          <tr key={`${r.month}-${r.reason}`}>
            <td className={td}>{formatIsoMonth(r.month)}</td>
            <td className={td}>{DELETION_REASON_LABELS[r.reason]}</td>
            <td className={`${td} tabular-nums`}>{r.count}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Gate 2b spec §6: month-start nudges queued per month, and how many of those nudged imported a
 *  statement within 3 days after it. Not range-bound, like the deletion reasons. */
function MonthStartNudges({ months }: { months: NonNullable<ActivationReport['monthStartNudges']> }) {
  if (months.length === 0) return <p className="p-4 text-sm text-muted">No month-start nudges sent yet.</p>;
  return (
    <table aria-label="Month-start nudges by month" className="w-full">
      <thead>
        <tr>
          <th className={th}>Month</th>
          <th className={th}>Nudges sent</th>
          <th className={th}>Imported within 3 days</th>
        </tr>
      </thead>
      <tbody>
        {months.map((m) => (
          <tr key={m.month}>
            <td className={td}>{formatIsoMonth(m.month)}</td>
            <td className={`${td} tabular-nums`}>{m.nudgesSent}</td>
            <td className={`${td} tabular-nums`}>{m.importedWithin3Days}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ActivationContent() {
  const [range, setRange] = useState(defaultRange);
  const { data: fetched, isLoading, isError } = useQuery({
    queryKey: ['admin-activation', range.from, range.to],
    queryFn: () => adminActivationApi.report(range.from, range.to),
    // The whole report refetches for a new range; keep the last one on screen meanwhile.
    placeholderData: keepPreviousData,
  });
  // The last report that loaded. keepPreviousData covers the wait, but React Query drops the kept
  // report once the new request fails; this keeps it, under a notice that it is the previous range.
  const [lastGood, setLastGood] = useState<ActivationReport | undefined>();
  useEffect(() => { if (fetched) setLastGood(fetched); }, [fetched]);
  const data = fetched ?? lastGood;
  // A start after the end (typed, which min/max do not stop) would be a 400 that blanks the page.
  const setFrom = (from: string) => { if (from && from <= range.to) setRange((r) => ({ ...r, from })); };
  const setTo = (to: string) => { if (to && to >= range.from) setRange((r) => ({ ...r, to })); };

  const [aboutOpen, setAboutOpen] = useState(false);

  return (
    <div className="space-y-8">
      {data && <Verdict report={data} />}
      <div>
        <button
          type="button"
          aria-expanded={aboutOpen}
          aria-controls="activation-about"
          onClick={() => setAboutOpen((v) => !v)}
          className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-ink"
        >
          <Info size={16} aria-hidden="true" />
          {aboutOpen ? 'Hide notes on these numbers' : 'About these numbers'}
        </button>
        <div id="activation-about" hidden={!aboutOpen} className="mt-2 text-sm text-muted space-y-1 max-w-2xl">
          {data && (
            <p>
              {data.funnelGoLive
                ? `Funnel steps that use the import-funnel record count from ${formatIsoDate(data.funnelGoLive)}.`
                : 'The import-funnel record has no rows yet.'}
            </p>
          )}
          {data && (
            <p>
              {data.sourceTrackingStart
                ? `Signup sources are recorded from ${istDate(data.sourceTrackingStart)}; earlier signups with nothing recorded show as unknown, not direct.`
                : 'When signup sources began to be recorded is not known, so signups with nothing recorded show as unknown.'}
            </p>
          )}
          {data?.landingPaths && (
            <p>
              {data.landingPaths.trackingStart
                ? `Landing pages are recorded from ${istDate(data.landingPaths.trackingStart)}; only signups made since then are counted in that table. A landing page is the first public Fynora page of the visit; the mobile apps and visits that began off the public pages have none.`
                : 'When landing pages began to be recorded is not known, so that table is empty.'}
            </p>
          )}
          <p>
            A second import is a second, different statement file that brought transactions in. The account sections
            of one statement, and a re-import of the same file, count as one file.
          </p>
          <p>
            Reached counts a signup at every step up to the furthest one they reached. Refused, read failed and held
            stop between choosing a file and having one read. Weeks run Monday to Sunday, IST.
          </p>
          <p>Only the answers follow the date range; every other count covers all signups.</p>
          <p>
            Import rate is got data in, of signups. A source or page with fewer than {MIN_SIGNUPS_TO_JUDGE} signups is
            marked too few to judge.
          </p>
          <p>Counts are small; one person changes them.</p>
        </div>
      </div>

      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {isError && !data && <p role="alert" className="text-sm text-danger">The activation report could not be loaded.</p>}
      {isError && data && (
        <p role="alert" className="text-sm text-danger">Could not load that range. Showing the previous report.</p>
      )}

      {data && (
        <>
          <Totals report={data} />

          <Funnel report={data} />

          <SourceRates rows={data.bySource} />

          {data.landingPaths && (
            <Section title="Signups by landing page">
              <LandingPathTable landing={data.landingPaths} />
            </Section>
          )}

          <section>
            <div className="flex flex-wrap items-end gap-3 mb-3">
              <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mr-auto">"What stopped you?" answers</h2>
              <label className="text-xs text-muted flex flex-col gap-1">
                Answers from
                <input
                  type="date"
                  value={range.from}
                  max={range.to}
                  onChange={(e) => setFrom(e.target.value)}
                  className="border border-border rounded-lg px-2 py-1 text-sm text-ink bg-card"
                />
              </label>
              <label className="text-xs text-muted flex flex-col gap-1">
                Answers to
                <input
                  type="date"
                  value={range.to}
                  min={range.from}
                  onChange={(e) => setTo(e.target.value)}
                  className="border border-border rounded-lg px-2 py-1 text-sm text-ink bg-card"
                />
              </label>
            </div>
            <div className="bg-card border border-border rounded-xl2 shadow-card">
              <AnswersByMoment answers={data.answers} />
            </div>
          </section>

          <Section title="Answers, focus and tracking by step">
            <ByStep report={data} />
          </Section>

          <Section title="Deletion reasons by month">
            <DeletionReasons reasons={data.deletionReasons} />
          </Section>

          <Section title="Month-start nudges">
            <MonthStartNudges months={data.monthStartNudges ?? []} />
          </Section>
        </>
      )}
    </div>
  );
}

export default function Activation() {
  return (
    <AdminLayout title="Activation" subtitle="Where each signup stopped on the way to a first statement, and what they said">
      <RequirePermission permission="PLATFORM_ANALYTICS_VIEW">
        <ActivationContent />
      </RequirePermission>
    </AdminLayout>
  );
}
