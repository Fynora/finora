import { ChevronLeft, ChevronRight } from 'lucide-react';
import { IconButton } from '../../design-system';
import { monthLabelLong } from './reportFormat';

/**
 * Previous / next arrows around a month picker that names months the way people say them
 * ("September 2026"), not "2026-09". `months` is oldest first, as the server sends it, so the left
 * arrow goes back in time. The picker keeps its raw "YYYY-MM" values so it can jump anywhere; the
 * arrows are disabled at either end instead of wrapping or doing nothing silently.
 */
export function MonthSwitcher({ months, month, onChange }: { months: string[]; month: string; onChange: (month: string) => void }) {
  const index = months.indexOf(month);
  const older = index > 0 ? months[index - 1] : null;
  const newer = index >= 0 && index < months.length - 1 ? months[index + 1] : null;
  return (
    <div>
      <label htmlFor="reports-month" className="block text-xs uppercase text-muted mb-1">Month</label>
      <div className="flex items-center gap-1">
        <IconButton
          aria-label="Previous month" icon={<ChevronLeft size={16} />} disabled={older === null}
          onClick={() => older && onChange(older)}
        />
        <select
          id="reports-month" value={month} onChange={(e) => onChange(e.target.value)}
          className="bg-card text-ink border rounded px-2 py-1.5 text-sm"
        >
          {months.map((m) => <option key={m} value={m}>{monthLabelLong(m)}</option>)}
        </select>
        <IconButton
          aria-label="Next month" icon={<ChevronRight size={16} />} disabled={newer === null}
          onClick={() => newer && onChange(newer)}
        />
      </div>
    </div>
  );
}
