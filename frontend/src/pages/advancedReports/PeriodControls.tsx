import type { AdvancedCompare, AdvancedPeriodInfo, AdvancedRange } from '../../api/endpoints';
import { Button } from '../../design-system';
import { compareOptionLabel, periodSpan, RANGE_OPTIONS } from './advancedFormat';

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
 * months bounded by the history. The line under the pickers names the months actually in the period.
 */
export function PeriodControls({
  choice, period, onChange, onExport, exportDisabled,
}: {
  choice: PeriodChoice; period: AdvancedPeriodInfo | null; onChange: (next: PeriodChoice) => void;
  onExport: () => void; exportDisabled: boolean;
}) {
  const options = period?.compareOptions ?? [];
  const span = period ? periodSpan(period) : null;
  const min = period?.firstDataMonth ?? undefined;
  const max = period?.newestDataMonth ?? undefined;
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
            <input
              id="advanced-start" type="month" value={choice.startMonth} min={min} max={choice.endMonth || max} className={FIELD}
              onChange={(e) => e.target.value && onChange({ ...choice, startMonth: e.target.value })}
            />
          </div>
          <div>
            <label htmlFor="advanced-end" className={LABEL}>To</label>
            <input
              id="advanced-end" type="month" value={choice.endMonth} min={choice.startMonth || min} max={max} className={FIELD}
              onChange={(e) => e.target.value && onChange({ ...choice, endMonth: e.target.value })}
            />
          </div>
        </>
      )}
      {options.length > 1 && period && (
        <div>
          <label htmlFor="advanced-compare" className={LABEL}>Compare with</label>
          <select
            id="advanced-compare" value={period.compare ?? options[0]} className={FIELD}
            onChange={(e) => onChange({ ...choice, compare: e.target.value as AdvancedCompare })}
          >
            {options.map((o) => <option key={o} value={o}>{compareOptionLabel(o, period)}</option>)}
          </select>
        </div>
      )}
      <div className="ml-auto">
        <Button variant="secondary" onClick={onExport} disabled={exportDisabled} className="uppercase">Export CSV</Button>
      </div>
    </div>
  );
}
