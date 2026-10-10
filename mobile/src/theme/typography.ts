import type { TextStyle } from 'react-native';
import { fonts } from './fonts';

/**
 * The dashboard's type ramp. Each entry names a role, not a size, so a card says what a piece of
 * text IS and the ramp decides how it looks.
 *
 * No fontWeight anywhere: every family name in fonts.ts is one specific weight's font file, and a
 * fontWeight beside it makes React Native synthesise a second bolding on top (see fonts.ts).
 */
export const typography = {
  display: { fontFamily: fonts.display, fontSize: 56, lineHeight: 58, letterSpacing: -2 },
  screenTitle: { fontFamily: fonts.display, fontSize: 26, lineHeight: 32, letterSpacing: -0.5 },
  cardTitle: { fontFamily: fonts.displayBold, fontSize: 16, lineHeight: 22, letterSpacing: -0.2 },
  numberL: { fontFamily: fonts.display, fontSize: 26, lineHeight: 30, letterSpacing: -0.6 },
  numberM: { fontFamily: fonts.displayBold, fontSize: 20, lineHeight: 24, letterSpacing: -0.4 },
  numberS: { fontFamily: fonts.displayBold, fontSize: 15, lineHeight: 20, letterSpacing: -0.2 },
  labelM: { fontFamily: fonts.bodySemibold, fontSize: 14, lineHeight: 20 },
  labelS: { fontFamily: fonts.bodySemibold, fontSize: 12, lineHeight: 16 },
  bodyM: { fontFamily: fonts.body, fontSize: 14, lineHeight: 20 },
  bodyS: { fontFamily: fonts.body, fontSize: 12, lineHeight: 17 },
  caption: { fontFamily: fonts.bodyMedium, fontSize: 11, lineHeight: 14 },
  // Uppercase by style, never by changing the string: tests and screen readers keep the real text.
  eyebrow: {
    fontFamily: fonts.bodySemibold, fontSize: 11, lineHeight: 14, letterSpacing: 0.9, textTransform: 'uppercase',
  },
} as const satisfies Record<string, TextStyle & { fontFamily: string; fontSize: number; lineHeight: number }>;
