import { Platform } from 'react-native';
import { fonts } from './fonts';
import { radius, spacing } from './palette';
import { setCustomFontsUnavailable, typography } from './typography';

// Every family name in fonts.ts carries its weight ("Manrope_800ExtraBold").
const weightOf = (family: string) => family.match(/_(\d00)/)?.[1];
const originalOS = Platform.OS;
afterEach(() => {
  (Platform as { OS: string }).OS = originalOS;
  setCustomFontsUnavailable(false);
});

describe('dashboard type ramp', () => {
  it('uses only font files the app loads', () => {
    const loaded = new Set<string>(Object.values(fonts));
    for (const [name, style] of Object.entries(typography)) {
      expect({ name, loaded: loaded.has(style.fontFamily ?? '') }).toEqual({ name, loaded: true });
    }
  });

  // Seen on an iPhone 17 Pro simulator: one launch came up without the custom fonts, and every
  // piece of dashboard text, the score and the amounts included, was drawn in the regular system
  // weight, because a step named a font file and nothing else. On iOS a named font ignores the
  // weight beside it, and a font that is not there falls back to the system font AT that weight.
  it('on iOS pairs each font file with that file\'s own weight', () => {
    (Platform as { OS: string }).OS = 'ios';
    for (const [name, style] of Object.entries(typography)) {
      expect({ name, weight: style.fontWeight }).toEqual({ name, weight: weightOf(style.fontFamily ?? '') });
    }
  });

  // fonts.ts: a weight beside a font file can make React Native synthesise a second bolding on
  // top of it. Not checked on Android for this change, so Android keeps the file name alone.
  it('on Android names the font file alone while the fonts are available', () => {
    (Platform as { OS: string }).OS = 'android';
    for (const [name, style] of Object.entries(typography)) {
      expect({ name, hasFamily: style.fontFamily != null, hasWeight: 'fontWeight' in style }).toEqual({ name, hasFamily: true, hasWeight: false });
    }
  });

  it.each(['ios', 'android'])('on %s, when the font files could not be loaded, every step is the system font at its own weight and size', (os) => {
    (Platform as { OS: string }).OS = os;
    const named = Object.fromEntries(Object.entries(typography).map(([name, style]) => [name, { ...style }]));
    setCustomFontsUnavailable(true);
    for (const [name, style] of Object.entries(typography)) {
      const { fontFamily, fontWeight: _namedWeight, ...metrics } = named[name];
      expect({ name, style }).toEqual({ name, style: { ...metrics, fontWeight: weightOf(fontFamily ?? '') } });
    }
  });

  it('goes back to the font files once they are available', () => {
    setCustomFontsUnavailable(true);
    setCustomFontsUnavailable(false);
    expect(typography.display.fontFamily).toBe(fonts.display);
  });

  it('hands back the same object for a step every time, so a style array does not change identity between renders', () => {
    expect(typography.numberL).toBe(typography.numberL);
    setCustomFontsUnavailable(true);
    expect(typography.numberL).toBe(typography.numberL);
  });

  it('gives every step a line height at least as tall as its font size', () => {
    for (const [name, style] of Object.entries(typography)) {
      expect({ name, ok: style.lineHeight >= style.fontSize }).toEqual({ name, ok: true });
    }
  });

  it('keeps the eyebrow uppercase through styling, so the underlying string is untouched', () => {
    expect(typography.eyebrow.textTransform).toBe('uppercase');
  });
});

describe('redesign spacing and radius steps', () => {
  it('adds the steps the design uses without moving the existing ones', () => {
    expect(spacing).toEqual({ xs: 4, sm: 8, ms: 12, md: 16, ml: 20, lg: 24, xl: 32 });
    expect(radius).toEqual({ md: 8, lg: 12, xl: 16, xxl: 20, hero: 28 });
  });
});
