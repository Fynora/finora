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
const ON_GLASS: Record<'light' | 'dark', (keyof Palette)[]> = {
  light: ['ink', 'muted', 'mutedInk', 'primary', 'brassInk', 'successInk', 'warningInk', 'dangerInk'],
  dark: ['ink', 'muted', 'mutedInk', 'primary', 'brassInk', 'successInk', 'warningInk', 'dangerInk'],
};
const ON_BACKDROP: (keyof Palette)[] = ['ink', 'muted', 'mutedInk', 'primary', 'brassInk'];

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
  const SCRIM = [0, 0, 0], SCRIM_ALPHA = 0.4; // QuickActionSheet's rgba(0,0,0,0.4) backdrop

  it('PNG matches mesh.json dimensions', () => {
    expect([png.width, png.height]).toEqual([mesh.width, mesh.height]);
  });

  it('every text token clears AA on a glass card, at every pixel', () => {
    expectAA(worst(ON_GLASS[theme], glass), 'glass card');
  });

  it('text that may sit directly on the backdrop clears AA at every pixel', () => {
    expectAA(worst(ON_BACKDROP, (px) => px), 'backdrop');
  });

  it('a glass sheet over the dimming scrim clears AA, whether the scrim covers bare mesh or a glass card', () => {
    expectAA(worst(ON_GLASS[theme], (px) => glass(over(SCRIM, SCRIM_ALPHA, px))), 'sheet/scrim/mesh');
    expectAA(worst(ON_GLASS[theme], (px) => glass(over(SCRIM, SCRIM_ALPHA, glass(px)))), 'sheet/scrim/card');
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
