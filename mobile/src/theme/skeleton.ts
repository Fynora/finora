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
  /** A line of the system font is this many times its font size tall -- measured on the simulator
   *  (iPhone 17 Pro, 2026-10-16: 11 -> 13.1, 12 -> 14.0, 14 -> 16.6, 17 -> 20.1). A text placeholder
   *  takes a whole line of room so the screen does not jump when the real text lands. */
  lineHeight: 1.19,
  /** Inside a paragraph the lines sit on top of each other, so each bar is drawn thinner than its
   *  line to stay visibly separate from the next. */
  paragraphBar: 0.7,
  /** Text that sets its own lineHeight instead of the system default. */
  textLine: {
    /** The settings screens' hints (General, Categorization, Account, the Active Sessions blurb):
     *  11pt on a 16pt line. */
    hint: 16,
    /** StatementHistoryScreen's body text: 13pt on an 18pt line. */
    statementBody: 18,
  },
  text: {
    axis: 9, tiny: 10, caption: 11, meta: 12, label: 13, body: 14, heading: 15, subheading: 16,
    subject: 17, amount: 18, value: 20, title: 22, hero: 28, display: 36,
  },
  control: {
    field: 48, button: 48, touch: 44, codeRow: 52,
    /** The slot TextField keeps under its input for an error message (minHeight 16). */
    fieldMessage: 16,
    /** The row Profile, Settings General and Categorization keep for SaveStatus above a Save
     *  button (saveRow, minHeight 16). */
    saveStatus: 16,
    /** SettingsGeneralScreen's theme switch: 44pt segments inside a 1pt border. */
    segments: 46,
    /** InvestmentsScreen's Delete link under a holding's value (minHeight 32). */
    rowAction: 32,
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
