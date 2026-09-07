const RADIUS = 70;
const HALF_CIRCUMFERENCE = Math.PI * RADIUS;

function zoneFor(score: number): 'red' | 'amber' | 'green' {
  if (score <= 30) return 'red';
  if (score <= 60) return 'amber';
  return 'green';
}

const ZONE_COLOR: Record<ReturnType<typeof zoneFor>, string> = {
  red: 'var(--color-danger)',
  amber: 'var(--color-warning)',
  green: 'var(--color-success)',
};

/**
 * Semi-circular 0-100 gauge with 3 color zones (red 0-30, amber 31-60, green 61-100) -- a coarser
 * read than the existing 4-tier scoreLabel/healthColor cutoffs (80/60/40) used for the text label
 * shown beneath this gauge. Both are deliberate: real credit-score dashboards commonly pair a
 * coarse gauge color with a finer text label.
 */
export function HealthScoreGauge({ score }: { score: number }) {
  const clamped = Math.max(0, Math.min(100, score));
  const zone = zoneFor(clamped);
  const fillLength = (clamped / 100) * HALF_CIRCUMFERENCE;

  return (
    <svg
      viewBox="0 0 160 90"
      className="w-full max-w-[220px]"
      role="img"
      aria-label={`Financial health score ${clamped} out of 100`}
    >
      <path
        d={`M 10 80 A ${RADIUS} ${RADIUS} 0 0 1 150 80`}
        fill="none"
        stroke="var(--color-border)"
        strokeWidth="14"
        strokeLinecap="round"
      />
      <path
        data-testid="health-score-gauge-fill"
        data-zone={zone}
        d={`M 10 80 A ${RADIUS} ${RADIUS} 0 0 1 150 80`}
        fill="none"
        stroke={ZONE_COLOR[zone]}
        strokeWidth="14"
        strokeLinecap="round"
        strokeDasharray={`${fillLength} ${HALF_CIRCUMFERENCE - fillLength}`}
      />
    </svg>
  );
}
