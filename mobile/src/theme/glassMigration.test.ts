import { readdirSync, readFileSync, statSync } from 'fs';
import { join, relative } from 'path';

/**
 * Source-scan guard for the glass redesign. Every opaque screen root moved to GlassScreen and every
 * opaque card surface to GlassSurface; what stays opaque on purpose (the lock cover, the crash
 * screen, chips and tiles that sit INSIDE a glass card) says so on the same line with a
 * `glass-exempt: <reason>` marker, visible in review. Per LINE, not per file: 20 files mix a
 * screen root with inner opaque uses, so a whole-file allowlist would either block legitimate
 * chips or hide a new opaque root.
 *
 * Inside a JSX style array the marker is a block comment (`{ backgroundColor: c.bg } /* glass-exempt: ... *\/`)
 * because a trailing `//` after JSX is text, not a comment; in plain TS either form works.
 */
const SRC = join(__dirname, '..');
const EXEMPT = /\/[/*]\s*glass-exempt:\s*\S/;
const files = (d: string): string[] => readdirSync(d).flatMap((f) => {
  const p = join(d, f);
  return statSync(p).isDirectory() ? files(p) : /\.tsx$/.test(f) && !/\.test\.tsx$/.test(f) ? [p] : [];
});
const unmarked = (pattern: RegExp, skip: string[] = []) => files(SRC).flatMap((p) => {
  const r = relative(SRC, p);
  if (skip.includes(r)) return [];
  return readFileSync(p, 'utf8').split('\n')
    .map((line, i) => ({ line, n: i + 1 }))
    .filter(({ line }) => pattern.test(line) && !EXEMPT.test(line))
    .map(({ n, line }) => `${r}:${n}: ${line.trim()}`);
});

it('every opaque c.bg background is either migrated to GlassScreen or marked glass-exempt', () => {
  // GlassScreen is the one place that legitimately paints opaque c.bg (under the mesh).
  expect(unmarked(/backgroundColor:\s*c\.bg\b/, ['components/GlassScreen.tsx'])).toEqual([]);
});

it('every opaque c.card background is either a GlassSurface or marked glass-exempt', () => {
  // GlassSurface's own Reduce-Transparency fallback is the one legitimate unmarked solid card.
  expect(unmarked(/backgroundColor:\s*c\.card\b/, ['components/GlassSurface.tsx'])).toEqual([]);
});

it('the lock cover is marked exempt (opaque on purpose), not migrated', () => {
  const src = readFileSync(join(SRC, 'components/AppLockGate.tsx'), 'utf8');
  expect(src).not.toMatch(/GlassScreen/);
  expect(src.match(/backgroundColor:\s*c\.bg\b.*glass-exempt/g)?.length).toBe(2);
});
