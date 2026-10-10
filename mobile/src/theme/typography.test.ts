import { fonts } from './fonts';
import { radius, spacing } from './palette';
import { typography } from './typography';

describe('dashboard type ramp', () => {
  it('uses only font files the app loads, and never a fontWeight on top of one', () => {
    const loaded = new Set<string>(Object.values(fonts));
    for (const [name, style] of Object.entries(typography)) {
      expect({ name, loaded: loaded.has(style.fontFamily) }).toEqual({ name, loaded: true });
      expect({ name, hasWeight: 'fontWeight' in style }).toEqual({ name, hasWeight: false });
    }
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
