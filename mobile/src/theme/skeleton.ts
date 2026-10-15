import { CASHFLOW_HEIGHT, DONUT_SIZE, TREND_HEIGHT } from '../lib/chartGeometry';
import { radius, type Palette } from './palette';

/**
 * Every value a loading placeholder draws with, named once and taken from the existing theme
 * rather than invented. Shimmer and every block in components/skeletons/ read from here; a source
 * scan (skeletonTokens.guard.test.ts) fails if one of them writes its own colour, radius, height
 * or width instead.
 *
 * Text placeholders are as tall as the font size of the text they stand in for (the convention the
 * first skeletons already used), so `text` mirrors the font sizes the converted screens use.
 * Controls copy the minimum heights of the real components: TextField's input row and Button are
 * 48, the touch-target floor is 44, iOS's Switch is 51 x 31.
 */
export const skeletonTokens = {
  /** Palette key of the placeholder fill. */
  fill: 'border' as const satisfies keyof Palette,
  /** The shared pulse: opacity sweeps low -> high -> low, each half taking halfCycleMs. */
  pulse: { low: 0.35, high: 1, halfCycleMs: 700, reducedMotion: 0.7 },
  radius: { line: radius.md, block: radius.md, card: radius.lg },
  text: {
    tiny: 10, caption: 11, meta: 12, label: 13, body: 14, heading: 15, subheading: 16,
    subject: 17, amount: 18, value: 20, title: 22, hero: 28, display: 36,
  },
  control: {
    field: 48, button: 48, touch: 44, codeRow: 52,
    switchHeight: 31, chip: 18, badge: 20, bar: 8, barTrack: 6,
  },
  /** Widths that are fixed in the real layout, not a share of the row. */
  fixedWidth: { amount: 60, switch: 51, stepperValue: 64 },
  icon: { sm: 32, md: 36, lg: 44, avatar: 52 },
  chart: { cashflow: CASHFLOW_HEIGHT, trend: TREND_HEIGHT, donut: DONUT_SIZE },
} as const;

export type SkeletonTextSize = keyof typeof skeletonTokens.text;
export type SkeletonControlSize = keyof typeof skeletonTokens.control;
export type SkeletonIconSize = keyof typeof skeletonTokens.icon;
