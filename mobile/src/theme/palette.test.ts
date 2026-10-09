import { glassFill, withAlpha } from './glass';
import { dark, light } from './palette';

/**
 * WCAG 2.x relative-luminance contrast ratio between two hex colors. Nothing in this repo computes
 * this for a raw hex pair -- the web side's a11y.measure.ts leans on axe, which needs a rendered
 * DOM and explicitly can't judge color-contrast under jsdom (see its own comment) -- so this is a
 * small, self-contained implementation rather than a partial one borrowed from a tool that can't
 * actually run it here.
 */
function channel(hex: number): number {
  const c = hex / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function luminance(hex: string): number {
  const n = parseInt(hex.replace('#', ''), 16);
  const r = channel((n >> 16) & 0xff);
  const g = channel((n >> 8) & 0xff);
  const b = channel(n & 0xff);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrastRatio(a: string, b: string): number {
  const [l1, l2] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (l1 + 0.05) / (l2 + 0.05);
}

// WCAG AA floor for text below 18pt (or below 14pt bold) -- see the Accessibility reference this
// review cites: "Up to 17 pts / All weights / 4.5:1 minimum contrast ratio". Every text token this
// app uses at those sizes must clear it against the two backgrounds it actually renders on.
const AA_SMALL_TEXT = 4.5;

describe('theme palette contrast', () => {
  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: mutedInk clears WCAG AA (4.5:1) against bg and card', (_name, p) => {
    expect(contrastRatio(p.mutedInk, p.bg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
    expect(contrastRatio(p.mutedInk, p.card)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  });

  it('muted clears AA on its own against bg and card (the glass redesign made the old margin unusable)', () => {
    // Until the glass redesign, light.muted sat at 4.55:1 on bg and relied on mutedInk for a real
    // margin. Muted text now also renders on translucent surfaces over the mesh backdrop
    // (glassContrast.test.ts measures those pixels), where the old value fell under AA -- so
    // muted itself has to clear AA with margin on the opaque surfaces too.
    for (const p of [light, dark]) {
      expect(contrastRatio(p.muted, p.bg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
      expect(contrastRatio(p.muted, p.card)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
    }
  });

  it("dark.mutedInk intentionally equals dark.muted, since dark theme already clears AA", () => {
    // The inverse of the light-mode guard above: dark.muted already sits at ~7.3:1 (see palette.ts's
    // comment), so mutedInk correctly makes no change there -- same shape as dark.warningInk
    // equalling dark.warning. Pinned explicitly so the two guards can't be satisfied by accident in
    // opposite directions (e.g. a future edit that darkens dark.mutedInk too, which the AA-floor
    // test alone wouldn't catch since a darker color still clears 4.5:1).
    expect(dark.mutedInk).toBe(dark.muted);
  });

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: warningInk still clears WCAG AA against warningBg (regression guard)', (_name, p) => {
    expect(contrastRatio(p.warningInk, p.warningBg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  });

  it('muted deliberately diverges from the web-shared --color-muted for the glass redesign', () => {
    // muted used to mirror frontend/src/index.css's --color-muted (#64748B / #98968F). The glass
    // redesign is mobile-only: web keeps opaque surfaces where those values clear AA, mobile puts
    // muted text on glass over a mesh where they measured 4.36:1 (light, on glass) and 3.86:1
    // (dark, directly on the backdrop). Pinned so the divergence stays a decision, not drift.
    expect(light.muted).toBe('#475569');
    expect(dark.muted).toBe('#B5B3AC');
  });

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: successInk clears WCAG AA (4.5:1) against successBg', (_name, p) => {
    expect(contrastRatio(p.successInk, p.successBg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  });

  it('light.successInk has a real margin over light.success, not just a token rename', () => {
    const before = contrastRatio(light.success, light.successBg);
    const after = contrastRatio(light.successInk, light.successBg);
    expect(after).toBeGreaterThan(before + 2.0);
  });

  it('dark.successInk intentionally equals dark.success, since dark theme already clears AA', () => {
    expect(dark.successInk).toBe(dark.success);
  });

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: dangerInk clears WCAG AA (4.5:1) against dangerBg, card and bg', (_name, p) => {
    expect(contrastRatio(p.dangerInk, p.dangerBg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
    expect(contrastRatio(p.dangerInk, p.card)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
    expect(contrastRatio(p.dangerInk, p.bg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  });

  it('light.danger alone does not clear AA on its own wash, which is why dangerInk exists', () => {
    expect(contrastRatio(light.danger, light.dangerBg)).toBeLessThan(AA_SMALL_TEXT);
  });

  it('dark.dangerInk is lighter than dark.danger: error text also lands on the dark mesh backdrop', () => {
    // Was equal to dark.danger until the glass redesign; #f87171 measured 4.13:1 directly on the
    // dark backdrop (glassContrast.test.ts), so text got its own lighter step while `danger` stays
    // the icon/border/amount tone. The *Ink token must stay the higher-contrast one on bg.
    expect(dark.dangerInk).not.toBe(dark.danger);
    expect(contrastRatio(dark.dangerInk, dark.bg)).toBeGreaterThan(contrastRatio(dark.danger, dark.bg));
  });

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: onPrimary is readable on primary (white is not, once dark mode makes primary light)', (_name, p) => {
    expect(contrastRatio(p.onPrimary, p.primary)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  });

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: planPlusText clears WCAG AA (4.5:1) against planPlusBg', (_name, p) => {
    expect(contrastRatio(p.planPlusText, p.planPlusBg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  });

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: planPremiumText clears WCAG AA (4.5:1) against planPremiumBg', (_name, p) => {
    expect(contrastRatio(p.planPremiumText, p.planPremiumBg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
  });

  it('dark.planPlusBg is not a near-duplicate of dark.bg (the badge must stay visible against the screen)', () => {
    // Unlike the wash tokens above (successBg/warningBg/planPremiumBg), PLUS is a solid chip
    // that's meant to read as a distinct badge, not a subtle tint -- see palette.ts's comment on
    // dark.planPlusBg. A future edit that quietly drifted it back toward dark.bg would still pass
    // every AA-text check above (the text/bg pair could still clear 4.5:1) while the badge itself
    // became invisible against the screen, so that failure mode needs its own guard.
    expect(contrastRatio(dark.planPlusBg, dark.bg)).toBeGreaterThan(4.5);
  });
});

describe('glass tokens', () => {
  it('withAlpha converts #RRGGBB to rgba', () => {
    expect(withAlpha('#262A33', 0.72)).toBe('rgba(38,42,51,0.72)');
    expect(withAlpha('#ffffff', 1)).toBe('rgba(255,255,255,1)');
  });

  it('withAlpha rejects malformed input instead of emitting a broken colour', () => {
    expect(() => withAlpha('#fff', 0.5)).toThrow();
    expect(() => withAlpha('#FFFFFF', 1.2)).toThrow();
    expect(() => withAlpha('#FFFFFF', -0.1)).toThrow();
  });

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: glassFill uses glassTint at glassAlpha', (_name, p) => {
    expect(glassFill(p)).toBe(withAlpha(p.glassTint, p.glassAlpha));
  });

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s: glassAlpha is the measured 0.72', (_name, p) => {
    // 0.72 is the value the 2026-10-09 contrast measurement cleared AA with; lowering it must go
    // back through glassContrast.test.ts, which reads this same token.
    expect(p.glassAlpha).toBe(0.72);
  });
});
