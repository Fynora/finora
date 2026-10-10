import { readFileSync } from 'fs';
import { join } from 'path';
import { PNG } from 'pngjs';
import { dark, light, type Palette } from './palette';

/**
 * Glass surfaces are translucent, so the colour under a line of text is not a palette token any
 * more -- it is whatever pixel of the mesh backdrop (assets/glass/mesh-*.png) happens to sit
 * behind it, composited with the glass tint. This reads the real committed PNGs and checks every
 * text token that can land on glass, on the bare backdrop, or on a sheet over the dimming scrim,
 * at EVERY pixel. A failure names theme / layer / token / x,y / ratio so the fix is a mesh.json
 * edit, never a relaxed threshold. palette.test.ts covers the opaque surfaces the same way.
 */
const ASSETS = join(__dirname, '../../assets/glass');
const mesh = JSON.parse(readFileSync(join(ASSETS, 'mesh.json'), 'utf8'));

const ch = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const lum = ([r, g, b]: number[]) => 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
const rgb = (hex: string) => { const n = parseInt(hex.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const ratio = (a: number[], b: number[]) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
const over = (top: number[], a: number, bot: number[]) => top.map((v, i) => v * a + bot[i] * (1 - a));

// Text tokens that can render on glass, and the subset that can render straight on the backdrop
// (screen headings and their metadata sit on GlassScreen with no card under them).
// Raw `danger`/`success`/`warning`/`brass` are deliberately absent: they are icon/border/fill
// tones, and glassMigration.test.ts guards that text never uses them (light danger measured 3.49:1
// on the backdrop, light success 3.02:1 on a glass card). Text uses the *Ink tokens below.
const TEXT_TOKENS: (keyof Palette)[] = ['ink', 'muted', 'mutedInk', 'primary', 'brassInk', 'successInk', 'warningInk', 'dangerInk'];
const ON_GLASS: Record<'light' | 'dark', (keyof Palette)[]> = { light: TEXT_TOKENS, dark: TEXT_TOKENS };
// Error/empty states render text straight on the screen root with no card under it, so every
// text token can land on the bare backdrop.
const ON_BACKDROP: (keyof Palette)[] = TEXT_TOKENS;
// Every scrim alpha a sheet/overlay paints behind a glass panel (grep rgba(0,0,0,…) in src): the
// darkest (TourOverlay 0.6) is the worst case for dark text in light mode, the lightest (0.35)
// for light text in dark mode, so all are checked rather than one representative value.
const SCRIM_ALPHAS = [0.35, 0.4, 0.45, 0.6];

function load(theme: string) {
  return PNG.sync.read(readFileSync(join(ASSETS, `mesh-${theme}.png`)));
}

describe.each([['light', light], ['dark', dark]] as const)('%s mesh', (theme, p) => {
  const png = load(theme);
  type Hit = { r: number; x: number; y: number };
  // EXHAUSTIVE: every pixel. `layer(px)` maps a backdrop pixel to the surface the text sits on.
  const worst = (tokens: (keyof Palette)[], layer: (px: number[]) => number[]) => {
    const res: Record<string, Hit> = {};
    for (let y = 0; y < png.height; y++) for (let x = 0; x < png.width; x++) {
      const i = (y * png.width + x) * 4;
      const surface = layer([png.data[i], png.data[i + 1], png.data[i + 2]]);
      for (const t of tokens) {
        const r = ratio(rgb(p[t] as string), surface);
        if (!(t in res) || r < res[t].r) res[t] = { r, x, y };
      }
    }
    return res;
  };
  const expectAA = (res: Record<string, Hit>, where: string) => {
    const fails = Object.entries(res)
      .filter(([, h]) => h.r < 4.5)
      .map(([t, h]) => `${theme} / ${where} / ${t} / x=${h.x}, y=${h.y} / ${h.r.toFixed(2)}:1 < 4.50:1`);
    expect(fails).toEqual([]);
  };
  const glass = (px: number[]) => over(rgb(p.glassTint), p.glassAlpha, px);
  const SCRIM = [0, 0, 0];

  it('PNG matches mesh.json dimensions', () => {
    expect([png.width, png.height]).toEqual([mesh.width, mesh.height]);
  });

  it('every text token clears AA on a glass card, at every pixel', () => {
    expectAA(worst(ON_GLASS[theme], glass), 'glass card');
  });

  // Financial Note: the brass wash at glass alpha instead of the neutral tint. Measured when
  // added: light 14.45 / 6.14 / 5.14, dark 14.36 / 8.03 / 7.04 at the worst pixel.
  it('the Financial Note text clears AA on brass glass, at every pixel', () => {
    const brassGlass = (px: number[]) => over(rgb(p.brassBg), p.glassAlpha, px);
    expectAA(worst(['ink', 'mutedInk', 'brassInk'], brassGlass), 'brass glass');
  });

  it('text that may sit directly on the backdrop clears AA at every pixel', () => {
    expectAA(worst(ON_BACKDROP, (px) => px), 'backdrop');
  });

  it.each(SCRIM_ALPHAS)('a glass sheet over a %s scrim clears AA, whether the scrim covers bare mesh or a glass card', (a) => {
    // 0.6 is TourOverlay's scrim alone, and its card renders only ink and muted (checked by grep,
    // 2026-10-09); every other sheet/overlay uses 0.35-0.45 and can carry any text token.
    const tokens = a === 0.6 ? (['ink', 'muted'] as (keyof Palette)[]) : ON_GLASS[theme];
    expectAA(worst(tokens, (px) => glass(over(SCRIM, a, px))), `sheet/scrim${a}/mesh`);
    expectAA(worst(tokens, (px) => glass(over(SCRIM, a, glass(px)))), `sheet/scrim${a}/card`);
  });

  it('edge band is flat base colour, so differently-sized crops meet without a seam', () => {
    const base = rgb(mesh.themes[theme].base);
    const band = Math.ceil(mesh.edgeBand * png.width);
    for (let y = 0; y < png.height; y++) for (let x = 0; x < png.width; x++) {
      if (x >= band && x < png.width - band && y >= band && y < png.height - band) continue;
      const i = (y * png.width + x) * 4;
      for (let k = 0; k < 3; k++) expect(Math.abs(png.data[i + k] - base[k])).toBeLessThanOrEqual(2);
    }
  });

  it('PNG base colour equals the palette bg it is drawn over', () => {
    expect(mesh.themes[theme].base.toLowerCase()).toBe(p.bg.toLowerCase());
  });
});
