import { Platform, type TextStyle } from 'react-native';
import { fonts } from './fonts';

type FontFile = (typeof fonts)[keyof typeof fonts];
type Metrics = Pick<TextStyle, 'letterSpacing' | 'textTransform'> & { fontSize: number; lineHeight: number };
type Step = Metrics & { fontFamily?: FontFile; fontWeight?: TextStyle['fontWeight'] };

/**
 * A step in three forms, built once so each is the same object on every read:
 *
 * - `named`: the font file alone. This is what fonts.ts asks for, and what Android uses.
 * - `weighted`: the font file plus that file's own weight. iOS uses this. A named font ignores
 *   the weight beside it, so nothing changes while the font is there (checked by pixel on an
 *   iOS 26 simulator); a font that is NOT there falls back to the system font at that weight
 *   instead of at regular.
 * - `system`: no font file, the weight alone. Used on both platforms once App.tsx reports that
 *   the font files could not be loaded.
 *
 * Why this exists: on an iPhone 17 Pro simulator one launch came up without the custom fonts,
 * and every piece of dashboard text, the score and the amounts included, was drawn at regular
 * weight, because a step named a font file and nothing else. The dashboard before the card
 * redesign mostly used the system font with a weight and kept its hierarchy in that situation.
 */
function step(fontFamily: FontFile, metrics: Metrics) {
  // Every family name in fonts.ts carries its weight: "Manrope_800ExtraBold".
  const fontWeight = /_(\d00)/.exec(fontFamily)![1] as TextStyle['fontWeight'];
  const named: Step = { fontFamily, ...metrics };
  const weighted: Step = { fontFamily, fontWeight, ...metrics };
  const system: Step = { fontWeight, ...metrics };
  return { named, weighted, system };
}

const steps = {
  display: step(fonts.display, { fontSize: 56, lineHeight: 58, letterSpacing: -2 }),
  screenTitle: step(fonts.display, { fontSize: 26, lineHeight: 32, letterSpacing: -0.5 }),
  cardTitle: step(fonts.displayBold, { fontSize: 16, lineHeight: 22, letterSpacing: -0.2 }),
  numberL: step(fonts.display, { fontSize: 26, lineHeight: 30, letterSpacing: -0.6 }),
  numberM: step(fonts.displayBold, { fontSize: 20, lineHeight: 24, letterSpacing: -0.4 }),
  numberS: step(fonts.displayBold, { fontSize: 15, lineHeight: 20, letterSpacing: -0.2 }),
  labelM: step(fonts.bodySemibold, { fontSize: 14, lineHeight: 20 }),
  labelS: step(fonts.bodySemibold, { fontSize: 12, lineHeight: 16 }),
  bodyM: step(fonts.body, { fontSize: 14, lineHeight: 20 }),
  bodyS: step(fonts.body, { fontSize: 12, lineHeight: 17 }),
  caption: step(fonts.bodyMedium, { fontSize: 11, lineHeight: 14 }),
  // Uppercase by style, never by changing the string: tests and screen readers keep the real text.
  eyebrow: step(fonts.bodySemibold, { fontSize: 11, lineHeight: 14, letterSpacing: 0.9, textTransform: 'uppercase' }),
};

let customFontsUnavailable = false;

/**
 * App.tsx calls this with `true` when the font files could not be loaded. It releases the splash
 * in that case and the app runs on the system font; this keeps the type ramp's weights when it
 * does. Screens mount only after the fonts have settled either way (FontsReadyProvider), so they
 * never read a step before this is known.
 */
export function setCustomFontsUnavailable(unavailable: boolean) {
  customFontsUnavailable = unavailable;
}

function pick(s: ReturnType<typeof step>): Step {
  if (customFontsUnavailable) return s.system;
  // Read on each use, like GlassSurface's platform check, so a test can drive both.
  return Platform.OS === 'ios' ? s.weighted : s.named;
}

/**
 * The dashboard's type ramp. Each entry names a role, not a size, so a card says what a piece of
 * text IS and the ramp decides how it looks.
 */
export const typography = {
  get display() { return pick(steps.display); },
  get screenTitle() { return pick(steps.screenTitle); },
  get cardTitle() { return pick(steps.cardTitle); },
  get numberL() { return pick(steps.numberL); },
  get numberM() { return pick(steps.numberM); },
  get numberS() { return pick(steps.numberS); },
  get labelM() { return pick(steps.labelM); },
  get labelS() { return pick(steps.labelS); },
  get bodyM() { return pick(steps.bodyM); },
  get bodyS() { return pick(steps.bodyS); },
  get caption() { return pick(steps.caption); },
  get eyebrow() { return pick(steps.eyebrow); },
};
