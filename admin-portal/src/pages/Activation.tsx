import { useEffect, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminActivationApi, type ActivationMomentName, type ActivationReport, type FunnelStep } from '../api/endpoints';
import {
  ANSWER_LABELS, DELETION_REASON_LABELS, FOCUS_LABELS, MOMENT_QUESTIONS, NO_ANSWER, NO_ANSWER_LABEL,
  SOURCE_LABELS, SPENDING_TRACKING_LABELS, STEP_LABELS, STEP_ORDER, formatDays, formatIsoDate, formatIsoMonth,
} from '../lib/activationLabels';

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

/** Spec §6.1: signups per IST week at their furthest step. A cell is a count and links nowhere. */
function FunnelTable({ weeks }: { weeks: ActivationReport['weeks'] }) {
  if (weeks.length === 0) return <p className="p-4 text-sm text-muted">No signups to count yet.</p>;
  return (
    <table aria-label="Funnel by signup week" className="w-full">
      <thead>
        <tr>
          <th className={th}>Signup week</th>
          {STEP_ORDER.map((step) => <th key={step} className={th}>{STEP_LABELS[step]}</th>)}
        </tr>
      </thead>
      <tbody>
        {weeks.map((w) => (
          <tr key={w.weekStart}>
            <td className={td}>{formatIsoDate(w.weekStart)}</td>
            {STEP_ORDER.map((step) => <td key={step} className={`${td} tabular-nums`}>{w.counts[step] ?? 0}</td>)}
          </tr>
        ))}
      </tbody>
    </table>
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

  return (
    <div className="space-y-8">
      <div className="text-sm text-muted space-y-1 max-w-2xl">
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
        <p>
          A second import is a second, different statement file that brought transactions in. The account sections
          of one statement, and a re-import of the same file, count as one file.
        </p>
        <p>Only the answers follow the date range; every other count covers all signups.</p>
        <p>Counts are small; one person changes them.</p>
      </div>

      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {isError && !data && <p role="alert" className="text-sm text-danger">The activation report could not be loaded.</p>}
      {isError && data && (
        <p role="alert" className="text-sm text-danger">Could not load that range. Showing the previous report.</p>
      )}

      {data && (
        <>
          <Totals report={data} />

          <Section title="Funnel by signup week">
            <FunnelTable weeks={data.weeks} />
          </Section>

          <Section title="Funnel by signup source">
            <SourceTable rows={data.bySource} />
          </Section>

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
