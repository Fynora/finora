import { readFileSync } from 'fs';
import { join } from 'path';
import { PNG } from 'pngjs';
import { dark, light } from '../theme/palette';
import { HERO_GLASS_ALPHA, heroTones } from './heroTones';

/**
 * The hero is tinted glass, so what sits under its text is the backdrop seen through the tint.
 * Like glassContrast.test.ts this reads the committed backdrop PNGs and checks every distinct
 * pixel, and it checks the opaque fallback (Reduce Transparency) as well. A failure is fixed by
 * changing a colour or HERO_GLASS_ALPHA in heroTones.ts, never by lowering 4.5 or 3.
 */
const ASSETS = join(__dirname, '../../assets/glass');
const ch = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const lum = ([r, g, b]: number[]) => 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
const ratio = (a: number[], b: number[]) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
// Resolves '#RRGGBB' or 'rgba(r,g,b,a)' to the colour actually seen over `under`.
const seen = (colour: string, under: number[]) => {
  if (colour.startsWith('#')) { const n = parseInt(colour.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; }
  const [r, g, b, a] = colour.match(/[\d.]+/g)!.map(Number);
  return [r, g, b].map((v, i) => v * a + under[i] * (1 - a));
};
const distinctPixels = (theme: string) => {
  const png = PNG.sync.read(readFileSync(join(ASSETS, `mesh-${theme}.png`)));
  const packed = new Set<number>();
  for (let i = 0; i < png.data.length; i += 4) packed.add((png.data[i] << 16) | (png.data[i + 1] << 8) | png.data[i + 2]);
  return [...packed].map((n) => [(n >> 16) & 255, (n >> 8) & 255, n & 255]);
};
const SCORES = [95, 70, 50, 20];

describe.each([['light', light], ['dark', dark]] as const)('hero tones in the %s theme', (theme, palette) => {
  const t = heroTones(palette);
  // Every surface the hero can be: the tint over each backdrop pixel, and the opaque fallback.
  const surfaces = [...distinctPixels(theme).map((px) => seen(t.surface, px)), seen(t.solidSurface, [0, 0, 0])];
  const worst = (colour: string) => Math.min(...surfaces.map((s) => ratio(seen(colour, s), s)));

  it('tints the glass with the surface token the hero has always used', () => {
    expect(t.solidSurface).toBe(palette.primaryDark);
    expect(seen(t.surface, [0, 0, 0])).toEqual(seen(palette.primaryDark, [0, 0, 0]).map((v) => v * HERO_GLASS_ALPHA));
  });

  // Sid, 2026-10-11, comparing the device with the approved design: the hero there is near-black
  // (#15171C). At a tint of 0.86 the page showed through enough to make it dark grey (#35373B
  // measured on an iPhone 17 Pro screenshot). In the light theme no channel of the hero, over any
  // backdrop pixel, may now be lighter than 40.
  if (theme === 'light') {
    it('is near-black over every backdrop pixel, as in the approved design', () => {
      const lightest = Math.max(...surfaces.flat());
      expect(lightest).toBeLessThanOrEqual(40);
    });
  }

  it.each(['text', 'textSoft'] as const)('%s clears 4.5:1 on the hero, on every backdrop pixel and on the solid fallback', (key) => {
    expect(worst(t[key])).toBeGreaterThanOrEqual(4.5);
  });

  it.each(SCORES)('the gauge colour for a score of %d clears 3:1 on the hero', (score) => {
    expect(worst(t.arc(score))).toBeGreaterThanOrEqual(3);
  });

  it('keeps the track fainter than any gauge colour, so progress is distinguishable from what remains', () => {
    const track = Math.max(...surfaces.map((s) => ratio(seen(t.track, s), s)));
    for (const score of SCORES) expect(worst(t.arc(score))).toBeGreaterThan(track);
  });
});
