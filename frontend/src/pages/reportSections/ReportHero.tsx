import type { ReportData } from '../../api/endpoints';
import { FinoraCard } from '../../design-system';
import { comparisonGateNote, comparisonMonthName, netChangePhrase } from '../../lib/reportComparison';
import { fmt, monthLabelLong } from './reportFormat';

const RING_RADIUS = 40;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

/**
 * The one sentence the month says about itself. The savings rate is the server's (the Dashboard's
 * own rule, carried on the report), never computed here: when the server withholds it, this says
 * why in plain words instead of showing a figure that unclassified money would decide.
 * Null when there is nothing honest to say (an older server, or a month with no activity at all).
 */
export function savingsHeadline(report: ReportData): string | null {
  const pct = report.savingsRatePct;
  if (pct != null) {
    const rounded = Math.round(Math.abs(pct));
    if (rounded === 0) return 'You broke even this month.';
    return pct > 0
      ? `You kept ${rounded}% of what you earned.`
      : `You spent ${rounded}% more than you earned.`;
  }
  switch (report.savingsRateGateReason) {
    case 'NO_INCOME':
      return report.expense > 0 ? 'No income was counted this month.' : null;
    case 'UNRESOLVED_EXCEEDS_INCOME':
      return "Money you received hasn't been sorted into income yet, so there is no savings rate.";
    default:
      return null;
  }
}

function SavingsRing({ pct }: { pct: number }) {
  // A negative rate fills nothing (the figure inside says -N%); only a positive one draws an arc.
  const filled = Math.min(Math.max(pct, 0), 100) / 100;
  return (
    <svg width="96" height="96" viewBox="0 0 96 96" role="img" aria-label={`Savings rate ${Math.round(pct)}%`} data-testid="hero-ring" className="flex-shrink-0">
      <circle cx="48" cy="48" r={RING_RADIUS} fill="none" strokeWidth="9" className="stroke-border" />
      {/* Only when something is filled: a zero-length arc with round ends still paints a dot. */}
      {filled > 0 && (
        <circle
          cx="48" cy="48" r={RING_RADIUS} fill="none" strokeWidth="9" strokeLinecap="round"
          strokeDasharray={`${filled * RING_CIRCUMFERENCE} ${RING_CIRCUMFERENCE}`}
          transform="rotate(-90 48 48)"
          className="stroke-success"
        />
      )}
      <text x="48" y="54" textAnchor="middle" className="fill-ink font-display" fontSize="20" fontWeight="700">
        {Math.round(pct)}%
      </text>
    </svg>
  );
}

/** Income split into what was spent and what was kept. Only drawn when income covers the spend: a
 *  bar of "spent" wider than the income it came from has no honest shape, and the headline already
 *  says so in words. */
function KeptSpentBar({ income, expense }: { income: number; expense: number }) {
  const spentPct = Math.min((expense / income) * 100, 100);
  return (
    <div data-testid="hero-bar">
      <div
        className="flex h-3.5 gap-0.5 overflow-hidden rounded-full"
        role="img"
        aria-label={`Of ${fmt(income)} earned, ${fmt(expense)} spent and ${fmt(income - expense)} kept`}
      >
        {spentPct > 0 && <div className="bg-danger" style={{ width: `${spentPct}%` }} />}
        {spentPct < 100 && <div className="bg-success" style={{ width: `${100 - spentPct}%` }} />}
      </div>
      <div className="mt-1.5 flex flex-wrap justify-between gap-x-4 gap-y-1 text-xs text-muted">
        <span><span className="text-danger" aria-hidden="true">●</span> Spent {fmt(expense)}</span>
        <span><span className="text-success" aria-hidden="true">●</span> Kept {fmt(income - expense)}</span>
      </div>
    </div>
  );
}

export function ReportHero({ report }: { report: ReportData }) {
  const net = report.income - report.expense;
  const cmp = report.comparison;
  const priorName = cmp ? comparisonMonthName(cmp.previousMonth, report.month) : null;
  const compare = priorName ? netChangePhrase(cmp, priorName, fmt) : null;
  const gateNote = priorName && !compare ? comparisonGateNote(cmp, monthLabelLong(report.month).split(' ')[0], priorName) : null;
  const headline = savingsHeadline(report);
  const netColor = net > 0 ? 'text-success' : net < 0 ? 'text-danger' : 'text-ink';
  // The bar is "kept" as a share of income, so it follows the rate's own gate: when the server
  // withholds the rate because the income itself is in doubt, the bar would show the same doubtful
  // share as a picture. An older server sends no reason, and then the bar is drawn as before.
  const showBar = report.income > 0 && report.expense <= report.income && !report.savingsRateGateReason;

  return (
    <FinoraCard tier="primary" padding="lg" className="space-y-5">
      <div className="flex flex-wrap items-center gap-6">
        {report.savingsRatePct != null && <SavingsRing pct={report.savingsRatePct} />}
        {/* min-w-[12rem]: below that the column wraps under the ring instead of squeezing beside it,
            which on a phone left the net figure room for only half its digits. */}
        <div className="min-w-[12rem] flex-1">
          <p className="text-xs uppercase tracking-wide text-muted">Net cash flow</p>
          <p data-testid="hero-net" className={`whitespace-nowrap font-display text-3xl font-extrabold tracking-tight sm:text-4xl ${netColor}`}>
            {net > 0 ? '+' : ''}{fmt(net)}
          </p>
          {headline && <p data-testid="hero-headline" className="mt-0.5 text-base font-medium text-ink">{headline}</p>}
          {compare && <p data-testid="hero-compare" className="text-sm text-muted">{compare}.</p>}
          {gateNote && <p data-testid="hero-compare-note" className="text-xs text-muted">{gateNote}</p>}
        </div>
      </div>

      {showBar && <KeptSpentBar income={report.income} expense={report.expense} />}

      {/* Said once: when the comparison note above already gives the reason this month is only
          partly in, a second line repeating it would just be noise. */}
      {report.complete === false && !gateNote && (
        <p data-testid="hero-partial" className="text-xs text-muted">
          Part of this month isn't in yet, so these figures may change.
        </p>
      )}
    </FinoraCard>
  );
}
