import { Link } from 'react-router-dom';
import type { DailySpend } from '../../api/endpoints';
import { FinoraCard, SectionHeader } from '../../design-system';
import { fmt, shortDate } from './reportFormat';
import { buildSpendCalendar, type SpendLevel } from './spendCalendar';

// Every pairing has to hold WCAG AA text contrast in BOTH themes (src/tailwindColorClasses.test.ts
// checks it): primary is dark in light mode and light in dark mode, so a mid-strength fill with
// either text colour can fall below 4.5:1. A day with no spend is an outline, not a fill.
const LEVEL_CLASS: Record<SpendLevel, string> = {
  0: 'border border-border text-muted',
  1: 'bg-primary/15 text-ink',
  2: 'bg-primary/30 text-ink',
  3: 'bg-primary/65 text-on-primary',
  4: 'bg-primary text-on-primary',
};

const CELL = 'flex aspect-square items-start justify-start rounded-md p-1 text-2xs font-medium';

/**
 * The month as a calendar, each day shaded by how much was spent that day relative to the month's
 * biggest day, with that day ringed and named. A day with spend opens that day in the Ledger. The
 * data is the report's own per-day spend (same rows and refund netting as the category list). Drawn
 * only when something was spent. A day with no shade means no spending was recorded that day, and
 * for a month that is only partly in the data the note says days past the data show nothing.
 */
export function SpendingCalendar({ month, days, complete }: { month: string; days: DailySpend[] | undefined; complete: boolean }) {
  if (!days || days.length === 0) return null;
  const { leadingBlanks, cells, biggest } = buildSpendCalendar(month, days);
  if (!biggest) return null;
  return (
    <FinoraCard>
      <SectionHeader title="Daily spending" size="sm" />
      <p data-testid="calendar-biggest" className="mb-2 text-xs text-muted">
        Biggest day: {shortDate(biggest.date)}, {fmt(biggest.amount)}
      </p>
      <div className="grid grid-cols-7 gap-1 text-center text-2xs text-muted" aria-hidden="true">
        {['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d, i) => <span key={i}>{d}</span>)}
      </div>
      <div className="mt-1 grid grid-cols-7 gap-1" data-testid="spend-calendar">
        {Array.from({ length: leadingBlanks }, (_, i) => <div key={`blank-${i}`} aria-hidden="true" />)}
        {cells.map((c) => {
          const ring = c.date === biggest.date ? ' ring-2 ring-ink ring-offset-1 ring-offset-card' : '';
          return c.amount > 0 ? (
            <Link
              key={c.date}
              to={`/app/transactions?${new URLSearchParams({ from: c.date, to: c.date }).toString()}`}
              aria-label={`${shortDate(c.date)}: ${fmt(c.amount)} spent. Open that day in the ledger.`}
              className={`${CELL} ${LEVEL_CLASS[c.level]}${ring}`}
            >
              {c.day}
            </Link>
          ) : (
            <div key={c.date} className={`${CELL} ${LEVEL_CLASS[0]}`}>{c.day}</div>
          );
        })}
      </div>
      <p className="mt-2 text-xs text-muted">
        A stronger shade means more spent that day.
        {complete ? '' : ' Days past your latest data show nothing because they are not in yet.'}
      </p>
    </FinoraCard>
  );
}
