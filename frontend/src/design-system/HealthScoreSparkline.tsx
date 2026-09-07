interface Point { yearMonth: string; score: number }

/** True when b is exactly one calendar month after a ("2026-05" after "2026-04"). */
function isNextMonth(a: string, b: string): boolean {
  const [ay, am] = a.split('-').map(Number);
  const [by, bm] = b.split('-').map(Number);
  const aTotal = ay * 12 + am;
  const bTotal = by * 12 + bm;
  return bTotal === aTotal + 1;
}

/** Splits points into contiguous-month runs, so a gap renders as a break, never interpolated. */
function splitIntoRuns(points: Point[]): Point[][] {
  if (points.length === 0) return [];
  const runs: Point[][] = [[points[0]]];
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

export function HealthScoreSparkline({ points }: { points: Point[] }) {
  const runs = splitIntoRuns(points).filter((run) => run.length >= 2);
  const width = 200;
  const height = 40;
  const xFor = (i: number) => (points.length <= 1 ? 0 : (i / (points.length - 1)) * width);
  const yFor = (score: number) => height - (score / 100) * height;

  return (
    <svg data-testid="health-score-sparkline" viewBox={`0 0 ${width} ${height}`} className="w-full h-10" aria-hidden="true">
      {runs.map((run) => (
        <polyline
          key={run[0].yearMonth}
          points={run.map((p) => `${xFor(points.indexOf(p))},${yFor(p.score)}`).join(' ')}
          fill="none"
          className="stroke-primary"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      ))}
    </svg>
  );
}
