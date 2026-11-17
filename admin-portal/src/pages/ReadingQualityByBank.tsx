import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Line, LineChart, Tooltip, YAxis } from 'recharts';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminReadingQualityApi } from '../api/endpoints';
import { withSep } from '../lib/withSep';
import type { ReadingQualityBankRow, ReadingQualityWeek, StagedOutcomesReport, StagedOutcomesWeek } from '../types';

type Period = 4 | 12;

const count = (n: number) => n.toLocaleString('en-IN');

/** 0.153 -> "15.3%", 0.2 -> "20%". */
export function percent(rate: number): string {
  return `${Number((rate * 100).toFixed(1))}%`;
}

/** A week's share held or failed, or null when nothing was tried that week (a gap, not 0%). */
export function weekRate(week: ReadingQualityWeek): number | null {
  const tried = week.statementsRead + week.failedReads;
  return tried === 0 ? null : (week.holds + week.failedReads) / tried;
}

/** "7 Sep": the start of a week, as the trend's tooltip names it. */
export function weekLabel(weekStart: string): string {
  return withSep(new Date(`${weekStart}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }));
}

/** Share held or failed, week by week, small enough to sit in a table cell. */
function Trend({ row }: { row: ReadingQualityBankRow }) {
  const data = row.weeks.map((w) => ({
    week: w.weekStart,
    rate: weekRate(w),
    problems: w.holds + w.failedReads,
    tried: w.statementsRead + w.failedReads,
  }));
  if (data.every((d) => d.rate == null)) return <span className="text-muted text-xs">—</span>;
  return (
    <LineChart width={140} height={32} data={data} margin={{ top: 4, right: 4, bottom: 4, left: 4 }}
      aria-label={`${row.bank} held or failed by week`}>
      <YAxis hide domain={[0, 'dataMax']} />
      <Tooltip
        labelFormatter={(_, payload) => {
          const week = payload?.[0]?.payload?.week as string | undefined;
          return week ? `Week of ${weekLabel(week)}` : '';
        }}
        formatter={(_value, _name, item) => {
          const p = item.payload as { rate: number | null; problems: number; tried: number };
          return [p.rate == null ? 'nothing tried' : `${percent(p.rate)} (${p.problems} of ${p.tried})`, 'Held or failed'];
        }}
        contentStyle={{ background: 'var(--color-card)', border: '1px solid var(--color-border)', borderRadius: 8, fontSize: 12 }}
      />
      <Line type="monotone" dataKey="rate" stroke="rgb(var(--color-primary))" strokeWidth={2}
        dot={{ r: 1.5 }} connectNulls={false} isAnimationActive={false} />
    </LineChart>
  );
}

/** The outcome columns, in the order a staged statement is counted under them. */
const OUTCOME_COLUMNS: [keyof StagedOutcomesWeek, string][] = [
  ['imported', 'Imported'],
  ['discardedByCustomer', 'Discarded by customer'],
  ['removedByPhone', 'Removed by phone app'],
  ['expired', 'Expired'],
  ['stillOpen', 'Still open'],
  ['notRecorded', 'Not recorded'],
];

const LIMIT_COLUMNS: [keyof StagedOutcomesWeek, string][] = [
  ['freeLimitShown', 'Limit shown'],
  ['freeLimitRefused', 'Confirm refused'],
  ['freeLimitThenImported', 'Shown, then imported'],
];

/**
 * How much of a week was recorded: 'none' when it ended before recording began (its outcomes read
 * "not recorded"), 'part' for the week recording began in, else 'all'. Dates are yyyy-mm-dd.
 */
export function recordedPart(weekStart: string | null, recordedSince: string | null): 'all' | 'part' | 'none' {
  if (!weekStart || !recordedSince || weekStart >= recordedSince) return 'all';
  const next = new Date(`${weekStart}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 7);
  return next.toISOString().slice(0, 10) <= recordedSince ? 'none' : 'part';
}

function OutcomeRow({ week, label, recordedSince, total }: {
  week: StagedOutcomesWeek; label: string; recordedSince: string | null; total?: boolean;
}) {
  const part = total ? 'all' : recordedPart(week.weekStart, recordedSince);
  const early = part === 'none';
  const cell = (key: keyof StagedOutcomesWeek, first = false) => (
    <td key={key} className={`px-3 py-2 text-right tabular-nums${first ? ' border-l border-border' : ''}`}>
      {count(week[key] as number)}
    </td>
  );
  return (
    <tr className={`border-b border-border last:border-0 ${total ? 'font-semibold text-ink' : ''} ${early ? 'text-muted' : ''}`}>
      <th scope="row" className="px-3 py-2 text-left font-medium whitespace-nowrap">
        {label}
        {part === 'none' && <span className="block text-xs font-normal">before recording began</span>}
        {part === 'part' && <span className="block text-xs font-normal text-muted">recording began this week</span>}
      </th>
      <td className="px-3 py-2 text-right tabular-nums">{count(week.staged)}</td>
      {OUTCOME_COLUMNS.map(([key], i) => cell(key, i === 0))}
      {LIMIT_COLUMNS.map(([key], i) => cell(key, i === 0))}
    </tr>
  );
}

const DISCARD_REASON_LABELS: [keyof StagedOutcomesWeek['discardReasons'], string][] = [
  ['wrongFile', 'Wrong file'],
  ['tooManyMonths', 'Covers too many months'],
  ['looksWrong', 'Something looks wrong'],
  ['later', 'Will upload it later'],
  ['other', 'Other'],
  ['noReason', 'No reason given'],
];

/** The customers' own reasons for discarding, over the whole period. */
function DiscardReasons({ total, weeks }: { total: StagedOutcomesWeek; weeks: number }) {
  if (total.discardedByCustomer === 0) return null;
  return (
    <p className="text-sm text-ink" data-testid="discard-reasons">
      <span className="text-muted">Why customers discarded, last {weeks} weeks: </span>
      {DISCARD_REASON_LABELS.filter(([key]) => total.discardReasons[key] > 0)
        .map(([key, label]) => `${label} ${count(total.discardReasons[key])}`)
        .join(' · ')}
    </p>
  );
}

/** "Staged, but was it imported?": what became of customers' staged statements, week by week. */
function StagedOutcomes({ period }: { period: Period }) {
  const { data, isLoading, isError } = useQuery<StagedOutcomesReport>({
    queryKey: ['reading-quality-staged-outcomes', period],
    queryFn: () => adminReadingQualityApi.stagedOutcomes(period),
  });

  return (
    <section aria-labelledby="staged-outcomes-heading" className="space-y-3">
      <div>
        <h2 id="staged-outcomes-heading" className="text-base font-semibold text-ink">Staged, but was it imported?</h2>
        <p className="text-xs text-muted">
          Statements that read cleanly for a customer, by the week they were staged, and what became of each.
          {data?.recordedSince && <> Recorded from {weekLabel(data.recordedSince)}.</>}
        </p>
      </div>
      {isLoading && !data ? (
        <p className="text-muted text-sm">Loading…</p>
      ) : isError && !data ? (
        <p className="text-muted text-sm">Could not load what became of staged statements.</p>
      ) : data ? (
        <div className="bg-card border border-border rounded-xl2 overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-xs text-muted">
                <th scope="col" rowSpan={2} className="px-3 py-2 text-left font-medium align-bottom border-b border-border">Week of</th>
                <th scope="col" rowSpan={2} className="px-3 py-2 text-right font-medium align-bottom border-b border-border">Staged</th>
                <th scope="colgroup" colSpan={OUTCOME_COLUMNS.length} className="px-3 pt-2 text-left font-medium border-l border-border">What became of it</th>
                <th scope="colgroup" colSpan={LIMIT_COLUMNS.length} className="px-3 pt-2 text-left font-medium border-l border-border">Free plan one-month limit</th>
              </tr>
              <tr className="text-xs text-muted border-b border-border">
                {OUTCOME_COLUMNS.map(([key, label], i) => (
                  <th key={key} scope="col" className={`px-3 py-2 text-right font-medium${i === 0 ? ' border-l border-border' : ''}`}>{label}</th>
                ))}
                {LIMIT_COLUMNS.map(([key, label], i) => (
                  <th key={key} scope="col" className={`px-3 py-2 text-right font-medium${i === 0 ? ' border-l border-border' : ''}`}>{label}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {[...data.weekly].reverse().map((week) => (
                <OutcomeRow key={week.weekStart} week={week} label={weekLabel(week.weekStart as string)}
                  recordedSince={data.recordedSince} />
              ))}
              <OutcomeRow week={data.total} label={`Last ${data.weeks} weeks`} recordedSince={data.recordedSince} total />
            </tbody>
          </table>
        </div>
      ) : null}
      {data && <DiscardReasons total={data.total} weeks={data.weeks} />}
      <p className="text-xs text-muted">
        Each staged statement counts once, under the first that applies: imported; discarded by the customer;
        removed by the phone app (it cannot import a statement covering more than one account, and deletes one
        itself); expired (left unconfirmed for 48 hours); still open (including any held for the team’s check);
        not recorded (staged before recording began, or removed by the system: a re-read, a rejected hold, a
        stale copy replaced on re-upload). Free plan limit: the review opened with the one-month notice, a
        confirm was refused for it, or it was shown and the statement imported anyway (after an upgrade, or as just
        its latest month).
        Reasons are the optional one-tap answer on the discard dialog; older apps never ask. Internal and admin
        accounts are left out.
      </p>
    </section>
  );
}

function ReadingQualityContent() {
  const [period, setPeriod] = useState<Period>(12);
  const { data, isLoading, isError } = useQuery({
    queryKey: ['reading-quality', period],
    queryFn: () => adminReadingQualityApi.report(period),
  });

  const unknownHolds = data ? data.bankNotKnown.importHolds + data.bankNotKnown.trustHolds : 0;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <div role="group" aria-label="Period" className="inline-flex rounded-lg border border-border overflow-hidden">
          {([4, 12] as const).map((p) => (
            <button
              key={p}
              type="button"
              aria-pressed={period === p}
              onClick={() => setPeriod(p)}
              className={`px-3 py-1.5 text-sm ${period === p ? 'bg-primary text-on-primary' : 'bg-card text-ink hover:bg-surface'}`}
            >
              Last {p} weeks
            </button>
          ))}
        </div>
        <p className="text-xs text-muted">
          Sorted by the share of statements held or failed, highest first. Banks with fewer than{' '}
          {data?.minStatements ?? 10} statements tried are too few to judge and sit at the bottom.
        </p>
      </div>

      {isLoading && !data ? (
        <p className="text-muted text-sm">Loading…</p>
      ) : isError && !data ? (
        <p className="text-muted text-sm">Could not load reading quality.</p>
      ) : data ? (
        <section className="bg-card border border-border rounded-xl2 overflow-x-auto">
          {data.banks.length === 0 && unknownHolds === 0 && data.bankNotKnown.fixes === 0 ? (
            <p className="text-muted text-sm p-6">No statements read in this period.</p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-muted border-b border-border">
                  <th scope="col" className="px-4 py-3 font-medium">Bank</th>
                  <th scope="col" className="px-4 py-3 font-medium text-right">Statements read</th>
                  <th scope="col" className="px-4 py-3 font-medium text-right">Rows read</th>
                  <th scope="col" className="px-4 py-3 font-medium text-right">Failed reads</th>
                  <th scope="col" className="px-4 py-3 font-medium text-right">Holds</th>
                  <th scope="col" className="px-4 py-3 font-medium text-right">Held or failed</th>
                  <th scope="col" className="px-4 py-3 font-medium text-right">Fixes</th>
                  <th scope="col" className="px-4 py-3 font-medium">Held or failed by week</th>
                </tr>
              </thead>
              <tbody>
                {data.banks.map((row) => (
                  <tr key={row.bank} className="border-b border-border last:border-0">
                    <th scope="row" className="px-4 py-2 text-left font-medium text-ink">{row.bank}</th>
                    <td className="px-4 py-2 text-right tabular-nums">{count(row.statementsRead)}</td>
                    <td className="px-4 py-2 text-right tabular-nums">{count(row.rowsRead)}</td>
                    <td className="px-4 py-2 text-right tabular-nums">{count(row.failedReads)}</td>
                    <td className="px-4 py-2 text-right tabular-nums">{count(row.holds)}</td>
                    <td className="px-4 py-2 text-right tabular-nums">
                      {row.problemRate == null
                        ? <span className="text-muted text-xs">too few to judge</span>
                        : <span className="font-semibold text-ink">{percent(row.problemRate)}</span>}
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums">{count(row.fixes)}</td>
                    <td className="px-4 py-2"><Trend row={row} /></td>
                  </tr>
                ))}
                {(unknownHolds > 0 || data.bankNotKnown.fixes > 0) && (
                  <tr className="text-muted">
                    <th scope="row" className="px-4 py-2 text-left font-medium">Bank not known</th>
                    <td className="px-4 py-2 text-right">—</td>
                    <td className="px-4 py-2 text-right">—</td>
                    <td className="px-4 py-2 text-right">—</td>
                    <td className="px-4 py-2 text-right tabular-nums">{count(unknownHolds)}</td>
                    <td className="px-4 py-2 text-right">—</td>
                    <td className="px-4 py-2 text-right tabular-nums">{count(data.bankNotKnown.fixes)}</td>
                    <td className="px-4 py-2 text-xs">
                      {count(data.bankNotKnown.importHolds)} import holds (read failed before the bank was named),{' '}
                      {count(data.bankNotKnown.trustHolds)} statement holds
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          )}
        </section>
      ) : null}

      <p className="text-xs text-muted">
        Failed reads: uploads the reader could not read for a reason of its own (no table found, no rows
        read, an image-only PDF, an unexpected error), once per file; wrong passwords and damaged files are
        not counted. Holds: statements held for review. Held or failed: (holds + failed reads) ÷ (statements
        read + failed reads). Fixes: a Claude fix that reached Fixed, or a fix reference an engineer recorded
        on a hold, once per hold. A file that names no bank counts under its layout profile's bank, as the
        customer saw it on the review screen. Internal and admin accounts are left out. Uploads from before
        bank names were recorded are not counted, and counts drop slightly when customers delete their
        accounts.
      </p>

      <StagedOutcomes period={period} />
    </div>
  );
}

export default function ReadingQualityByBank() {
  return (
    <AdminLayout title="Reading quality by bank" subtitle="Which bank's statements the reader handles worst — bank names and counts only.">
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <ReadingQualityContent />
      </RequirePermission>
    </AdminLayout>
  );
}
