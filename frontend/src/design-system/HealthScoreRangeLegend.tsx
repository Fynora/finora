const TIERS = [
  { range: '0-40', label: 'Needs Attention', min: 0, max: 40 },
  { range: '41-60', label: 'Fair', min: 41, max: 60 },
  { range: '61-80', label: 'Good', min: 61, max: 80 },
  { range: '81-100', label: 'Excellent', min: 81, max: 100 },
] as const;

/**
 * Answers "what does 51 mean, how far to the next tier" directly under the gauge -- unlike a
 * credit score, this score has no externally-understood meaning on its own.
 */
export function HealthScoreRangeLegend({ score }: { score: number }) {
  return (
    <div className="space-y-1">
      {TIERS.map((tier) => {
        const isCurrent = score >= tier.min && score <= tier.max;
        return (
          <div
            key={tier.range}
            data-current={isCurrent}
            className={`flex items-center justify-between text-[11px] rounded px-1.5 py-0.5 ${
              isCurrent ? 'bg-surface font-semibold text-ink' : 'text-muted'
            }`}
          >
            <span>{tier.range}</span>
            <span>{tier.label}</span>
          </div>
        );
      })}
    </div>
  );
}
