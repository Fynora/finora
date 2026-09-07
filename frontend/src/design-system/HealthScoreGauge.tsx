import { useEffect, useState } from 'react';

const RADIUS = 70;
const HALF_CIRCUMFERENCE = Math.PI * RADIUS;
const FILL_DURATION_MS = 900;

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
 *
 * The fill animates in via a CSS transition on `stroke-dasharray`, not a JS rAF loop -- the same
 * technique the landing page's HealthScoreRing uses for its own ring fill. Starts at 0 on mount
 * and transitions to the real score; a later score change (e.g. a background refetch landing a
 * new value) animates smoothly from whatever the gauge was already showing, not a jarring reset
 * back to 0 first.
 */
export function HealthScoreGauge({ score }: { score: number }) {
  const clamped = Math.max(0, Math.min(100, score));
  const zone = zoneFor(clamped);

  const [animatedScore, setAnimatedScore] = useState(0);
  useEffect(() => {
    setAnimatedScore(clamped);
  }, [clamped]);

  const reducedMotion = typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  const displayScore = reducedMotion ? clamped : animatedScore;
  const fillLength = (displayScore / 100) * HALF_CIRCUMFERENCE;

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
        style={reducedMotion ? undefined : { transition: `stroke-dasharray ${FILL_DURATION_MS}ms cubic-bezier(0.16,1,0.3,1)` }}
      />
    </svg>
  );
}
