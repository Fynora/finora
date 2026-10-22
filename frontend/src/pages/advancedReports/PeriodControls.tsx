import type { AdvancedCompare, AdvancedPeriodInfo, AdvancedRange } from '../../api/endpoints';
import { Button } from '../../design-system';
import { compareOptionLabel, monthChoices, monthYear, periodSpan, RANGE_OPTIONS } from './advancedFormat';

export interface PeriodChoice {
  range: AdvancedRange;
  startMonth: string;
  endMonth: string;
  compare: AdvancedCompare | null;
}

const FIELD = 'bg-card text-ink border border-border rounded-lg px-2.5 py-1.5 text-sm';
const LABEL = 'mb-1 block text-xs font-medium uppercase text-muted';

/**
 * The one period every figure on the Overview, Spending, Income and Merchants tabs answers to, and
 * what it is compared with. The compare picker appears only when there is more than one way to
 * compare (a 12-month period, this year and last year have exactly one), and a custom run takes two
 * months from the history. The line under the pickers names the months actually in the period.
 */
export function PeriodControls({
  choice, period, onChange, onExport, onPrint, exportDisabled,
}: {
  choice: PeriodChoice; period: AdvancedPeriodInfo | null; onChange: (next: PeriodChoice) => void;
  onExport: () => void; onPrint: () => void; exportDisabled: boolean;
}) {
  // While a new period loads, `period` is still the one on screen: its months and its ways to compare
  // belong to the old choice, so they are not shown beside the new one.
  const current = period !== null && period.range === choice.range ? period : null;
  const options = current?.compareOptions ?? [];
  const span = current ? periodSpan(current) : null;
  // A select, not <input type="month">: Firefox and desktop Safari draw that as a plain text box, where
  // typing a half-finished month would be thrown away on every keystroke.
  const months = monthChoices(period?.firstDataMonth ?? null, period?.newestDataMonth ?? null, [choice.startMonth, choice.endMonth]);
  return (
    <div className="flex flex-wrap items-end gap-3">
      <div>
        <label htmlFor="advanced-range" className={LABEL}>Period</label>
        <select
          id="advanced-range" value={choice.range} className={FIELD}
          onChange={(e) => onChange({ ...choice, range: e.target.value as AdvancedRange })}
        >
          {RANGE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        {span && choice.range !== 'CUSTOM' && <p className="mt-1 text-2xs text-muted" data-testid="period-span">{span}</p>}
      </div>
      {choice.range === 'CUSTOM' && (
        <>
          <div>
            <label htmlFor="advanced-start" className={LABEL}>From</label>
            <select
              id="advanced-start" value={choice.startMonth} className={FIELD}
              onChange={(e) => onChange({ ...choice, startMonth: e.target.value })}
            >
              {choice.startMonth === '' && <option value="">Choose…</option>}
              {months.map((m) => <option key={m} value={m}>{monthYear(m)}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="advanced-end" className={LABEL}>To</label>
            <select
              id="advanced-end" value={choice.endMonth} className={FIELD}
              onChange={(e) => onChange({ ...choice, endMonth: e.target.value })}
            >
              {choice.endMonth === '' && <option value="">Choose…</option>}
              {months.map((m) => <option key={m} value={m}>{monthYear(m)}</option>)}
            </select>
          </div>
        </>
      )}
      {options.length > 1 && current && (
        <div>
          <label htmlFor="advanced-compare" className={LABEL}>Compare with</label>
          <select
            id="advanced-compare" value={current.compare ?? options[0]} className={FIELD}
            onChange={(e) => onChange({ ...choice, compare: e.target.value as AdvancedCompare })}
          >
            {options.map((o) => <option key={o} value={o}>{compareOptionLabel(o, current)}</option>)}
          </select>
        </div>
      )}
      <div className="ml-auto flex gap-2">
        <Button variant="secondary" onClick={onExport} disabled={exportDisabled} className="uppercase">Export CSV</Button>
        <Button onClick={onPrint} disabled={exportDisabled} className="uppercase">Print / PDF</Button>
      </div>
    </div>
  );
}
