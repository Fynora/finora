import Svg, { Polyline } from 'react-native-svg';
import type { HealthScorePoint } from '../../types';

const WIDTH = 200;
const HEIGHT = 40;

/** True when b is exactly one calendar month after a ("2026-05" after "2026-04"). */
function isNextMonth(a: string, b: string): boolean {
  const [ay, am] = a.split('-').map(Number);
  const [by, bm] = b.split('-').map(Number);
  return by * 12 + bm === ay * 12 + am + 1;
}

/** Splits points into contiguous-month runs, so a gap renders as a break, never interpolated --
 *  ported from frontend/src/design-system/HealthScoreSparkline.tsx's identical function. */
function splitIntoRuns(points: HealthScorePoint[]): HealthScorePoint[][] {
  if (points.length === 0) return [];
  const runs: HealthScorePoint[][] = [[points[0]]];
  for (let i = 1; i < points.length; i++) {
    const prev = points[i - 1];
    const cur = points[i];
    if (isNextMonth(prev.yearMonth, cur.yearMonth)) {
      runs[runs.length - 1].push(cur);
    } else {
      runs.push([cur]);
    }
  }
  return runs;
}

/**
 * Mobile port of frontend/src/design-system/HealthScoreSparkline.tsx. Not animated via
 * RevealPolyline (unlike CashFlowChart/TrendChart): this renders inside HealthHero, which
 * already staggers its own reveal, and a 6-point sparkline is small enough that a static draw
 * reads as part of the hero settling in rather than a separate animation competing with it.
 */
export function HealthSparkline({ points, color }: { points: HealthScorePoint[]; color: string }) {
  const runs = splitIntoRuns(points).filter((run) => run.length >= 2);
  if (runs.length === 0) return null;

  const xAt = (i: number) => (points.length <= 1 ? 0 : (i / (points.length - 1)) * WIDTH);
  const yAt = (score: number) => HEIGHT - (score / 100) * HEIGHT;

  return (
    <Svg testID="health-sparkline" width="100%" height={HEIGHT} viewBox={`0 0 ${WIDTH} ${HEIGHT}`}>
      {runs.map((run) => (
        <Polyline
          key={run[0].yearMonth}
          points={run.map((p) => `${xAt(points.indexOf(p))},${yAt(p.score)}`).join(' ')}
          fill="none"
          stroke={color}
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      ))}
    </Svg>
  );
}
