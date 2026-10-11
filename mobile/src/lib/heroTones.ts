import { dark, light, type Palette } from '../theme/palette';
import { withAlpha } from '../theme/glass';

/**
 * How opaque the hero's glass tint is. Far higher than the app's glass alpha (0.72) on purpose.
 *
 * - Contrast: the backdrop shows through the tint, and at 0.72 the dark theme's soft text
 *   measured 4.13:1 and three gauge colours fell under 3:1.
 * - The approved look (Sid, 2026-10-11): the hero is near-black in the light theme. At 0.86 it
 *   cleared contrast but the near-white page showed through enough to make it dark grey
 *   (#35373B measured on an iPhone 17 Pro screenshot, against the design's #15171C). At 0.96 no
 *   channel is lighter than 40 over any backdrop pixel.
 *
 * heroTones.test.ts measures this value on every backdrop pixel; lowering it has to go back
 * through that test.
 */
export const HERO_GLASS_ALPHA = 0.96;

export interface HeroTones {
  /** The glass tint: `primaryDark` at HERO_GLASS_ALPHA. */
  surface: string;
  /** The opaque fill under Reduce Transparency, and the colour of text on the hero's own button. */
  solidSurface: string;
  text: string;
  textSoft: string;
  track: string;
  divider: string;
  arc: (score: number) => string;
}

/**
 * Every colour drawn on the health hero. The hero is glass tinted with `primaryDark`, which is
 * near-black in the light theme and light cream in the dark theme, so the hero is the one place
 * where a theme's own semantic tones are on the wrong ground: dark-theme green on cream measured
 * 1.45:1. Each theme therefore borrows the tones made for the opposite ground.
 * heroTones.test.ts measures every value here against the tint over every backdrop pixel, and
 * against the opaque fallback, on both themes; a change that fails it is a change to these
 * choices, never to the thresholds.
 */
export function heroTones(c: Palette): HeroTones {
  const surfaceIsDark = c !== dark; // light theme -> near-black hero; dark theme -> cream hero
  const tier = surfaceIsDark
    ? { excellent: dark.success, fair: dark.warning, poor: dark.danger }
    : { excellent: light.successInk, fair: light.warningInk, poor: light.dangerInk };
  return {
    surface: withAlpha(c.primaryDark, HERO_GLASS_ALPHA),
    solidSurface: c.primaryDark,
    text: c.onPrimary,
    textSoft: withAlpha(c.onPrimary, 0.72),
    track: withAlpha(c.onPrimary, 0.16),
    divider: withAlpha(c.onPrimary, 0.12),
    arc: (score: number) => {
      if (score >= 80) return tier.excellent;
      if (score >= 60) return c.primaryLight; // the neutral "Good" tier: no alarm colour
      if (score >= 40) return tier.fair;
      return tier.poor;
    },
  };
}
