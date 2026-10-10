/**
 * Geometry for the health hero's dial: a 270 degree arc that starts at the bottom-left and runs
 * clockwise to the bottom-right. Angles are in SVG's own frame (0 = three o'clock, y grows
 * downward, positive = clockwise), so no flipping happens anywhere.
 *
 * Both functions are worklets: the hero animates the score on the UI thread and builds the path
 * there. They are plain functions as well, so the static track and the tests call them directly.
 */
export const GAUGE_SIZE = 116;
export const GAUGE_STROKE = 9;
export const GAUGE_RADIUS = (GAUGE_SIZE - GAUGE_STROKE) / 2;
const CENTER = GAUGE_SIZE / 2;
const START_DEG = 135;
const SWEEP_DEG = 270;

function pointAt(deg: number): { x: number; y: number } {
  'worklet';
  const a = (deg * Math.PI) / 180;
  return { x: CENTER + GAUGE_RADIUS * Math.cos(a), y: CENTER + GAUGE_RADIUS * Math.sin(a) };
}

export function gaugeArcPath(score: number): string {
  'worklet';
  const s = Math.max(0, Math.min(100, score));
  if (!(s > 0)) return '';
  const sweep = (SWEEP_DEG * s) / 100;
  const from = pointAt(START_DEG);
  const to = pointAt(START_DEG + sweep);
  return `M ${from.x} ${from.y} A ${GAUGE_RADIUS} ${GAUGE_RADIUS} 0 ${sweep > 180 ? 1 : 0} 1 ${to.x} ${to.y}`;
}
