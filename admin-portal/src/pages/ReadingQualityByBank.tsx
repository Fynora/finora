import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Line, LineChart, Tooltip, YAxis } from 'recharts';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { adminReadingQualityApi } from '../api/endpoints';
import { withSep } from '../lib/withSep';
import type { ReadingQualityBankRow, ReadingQualityWeek } from '../types';

type Period = 4 | 12;

const count = (n: number) => n.toLocaleString('en-IN');

/** 0.153 -> "15.3%", 0.2 -> "20%". */
export function percent(rate: number): string {
  return `${Number((rate * 100).toFixed(1))}%`;
}

/** A week's hold rate, or null when nothing was read that week (a gap, not 0%). */
export function weekRate(week: ReadingQualityWeek): number | null {
  return week.statementsRead === 0 ? null : week.holds / week.statementsRead;
}

/** "7 Sep": the start of a week, as the trend's tooltip names it. */
export function weekLabel(weekStart: string): string {
  return withSep(new Date(`${weekStart}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }));
}

/** Hold rate week by week, small enough to sit in a table cell. */
function Trend({ row }: { row: ReadingQualityBankRow }) {
  const data = row.weeks.map((w) => ({ week: w.weekStart, rate: weekRate(w), holds: w.holds, read: w.statementsRead }));
  if (data.every((d) => d.rate == null)) return <span className="text-muted text-xs">—</span>;
  return (
    <LineChart width={140} height={32} data={data} margin={{ top: 4, right: 4, bottom: 4, left: 4 }}
      aria-label={`${row.bank} hold rate by week`}>
      <YAxis hide domain={[0, 'dataMax']} />
      <Tooltip
        labelFormatter={(_, payload) => {
          const week = payload?.[0]?.payload?.week as string | undefined;
          return week ? `Week of ${weekLabel(week)}` : '';
        }}
        formatter={(_value, _name, item) => {
          const p = item.payload as { rate: number | null; holds: number; read: number };
          return [p.rate == null ? 'nothing read' : `${percent(p.rate)} (${p.holds} of ${p.read})`, 'Hold rate'];
        }}
        contentStyle={{ background: 'var(--color-card)', border: '1px solid var(--color-border)', borderRadius: 8, fontSize: 12 }}
      />
      <Line type="monotone" dataKey="rate" stroke="rgb(var(--color-primary))" strokeWidth={2}
        dot={{ r: 1.5 }} connectNulls={false} isAnimationActive={false} />
    </LineChart>
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
          Sorted by hold rate, highest first. Banks with fewer than {data?.minStatements ?? 10} statements read are
          too few to judge and sit at the bottom.
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
                  <th scope="col" className="px-4 py-3 font-medium text-right">Holds</th>
                  <th scope="col" className="px-4 py-3 font-medium text-right">Hold rate</th>
                  <th scope="col" className="px-4 py-3 font-medium text-right">Fixes</th>
                  <th scope="col" className="px-4 py-3 font-medium">Hold rate by week</th>
                </tr>
              </thead>
              <tbody>
                {data.banks.map((row) => (
                  <tr key={row.bank} className="border-b border-border last:border-0">
                    <th scope="row" className="px-4 py-2 text-left font-medium text-ink">{row.bank}</th>
                    <td className="px-4 py-2 text-right tabular-nums">{count(row.statementsRead)}</td>
                    <td className="px-4 py-2 text-right tabular-nums">{count(row.rowsRead)}</td>
                    <td className="px-4 py-2 text-right tabular-nums">{count(row.holds)}</td>
                    <td className="px-4 py-2 text-right tabular-nums">
                      {row.holdRate == null
                        ? <span className="text-muted text-xs">too few to judge</span>
                        : <span className="font-semibold text-ink">{percent(row.holdRate)}</span>}
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
        Holds: statements held for review. Fixes: a Claude fix that reached Fixed, or a fix reference an
        engineer recorded on a hold — once per hold. Uploads from before bank names were recorded are not
        counted, and counts drop slightly when customers delete their accounts.
      </p>
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
