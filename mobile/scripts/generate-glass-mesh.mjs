// Deterministic mesh-gradient backdrop for the glass UI. Reads assets/glass/mesh.json and writes
// mesh-<theme>.png. Each blob is a smoothstep radial falloff from `peak` opacity at its centre to
// 0 at radius r (fraction of width), source-over composited onto `base`. A final mask forces the
// outer `edgeBand` to pure base so any crop of the image meets flat colour at its edges.
// Re-run after changing mesh.json: `npm run generate:glass-mesh`; glassContrast.test.ts gates it.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PNG } from 'pngjs';

const dir = join(dirname(fileURLToPath(import.meta.url)), '../assets/glass');
const spec = JSON.parse(readFileSync(join(dir, 'mesh.json'), 'utf8'));
const rgb = (h) => { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const smooth = (t) => t * t * (3 - 2 * t);

for (const [theme, { base, blobs }] of Object.entries(spec.themes)) {
  const { width: W, height: H, edgeBand } = spec;
  const png = new PNG({ width: W, height: H });
  const b = rgb(base);
  const band = edgeBand * W;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let px = [...b];
    for (const blob of blobs) {
      const dx = x - blob.cx * W, dy = y - blob.cy * H;
      const d = Math.sqrt(dx * dx + dy * dy) / (blob.r * W);
      if (d >= 1) continue;
      const a = blob.peak * smooth(1 - d);
      const c = rgb(blob.color);
      px = px.map((v, k) => c[k] * a + v * (1 - a));
    }
    // Fade to base across a second band inside the hard edge band, so the mask has no visible line.
    const e = Math.min(x, y, W - 1 - x, H - 1 - y);
    const m = e <= band ? 0 : e >= 2 * band ? 1 : smooth((e - band) / band);
    const i = (y * W + x) * 4;
    for (let k = 0; k < 3; k++) png.data[i + k] = Math.round(b[k] + (px[k] - b[k]) * m);
    png.data[i + 3] = 255;
  }
  writeFileSync(join(dir, `mesh-${theme}.png`), PNG.sync.write(png));
  console.log(`wrote mesh-${theme}.png`);
}
