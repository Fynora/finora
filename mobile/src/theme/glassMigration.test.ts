import { execFileSync } from 'child_process';
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

it('danger/success are never TEXT colours: text uses dangerInk/successInk', () => {
  // Measured on the real mesh (glassContrast.test.ts, 2026-10-09): light danger 3.49:1 on the
  // backdrop, light success 3.02:1 on a glass card (and 3.30 on the old opaque card -- it never
  // cleared AA). `color={c.danger}` on an icon is fine (graphical objects need 3:1); `color: c.x`
  // inside a style object is text, and conditional expressions (`x ? c.success : c.danger`) count.
  expect(unmarked(/\bcolor:\s*[^,}]*\bc\.(danger|success)\b/)).toEqual([]);
});

it('app version differs from origin/main once native glass modules are added (OTA must not reach old binaries)', () => {
  // runtimeVersion.policy is 'appVersion': an OTA reaches every binary of the same version. JS
  // that imports expo-glass-effect / expo-blur would crash a binary built without them, so the
  // commit that adds the modules must also move `version`. Vacuous once main carries the module.
  const pkg = JSON.parse(readFileSync(join(SRC, '../package.json'), 'utf8'));
  if (!pkg.dependencies['expo-glass-effect']) return;
  // A depth-1 checkout has no origin/main; the Mobile CI job fetches the ref for this test, and a
  // local clone gets it from `git fetch origin main`. Say so instead of a bare git error.
  const onMain = (file: string) => {
    try {
      return execFileSync('git', ['show', `origin/main:mobile/${file}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    } catch {
      throw new Error(`origin/main is not available in this checkout, so ${file} cannot be compared with main's. Run: git fetch --no-tags --depth=1 origin +refs/heads/main:refs/remotes/origin/main`);
    }
  };
  const mainCfg = onMain('app.config.ts');
  const mainPkg = JSON.parse(onMain('package.json'));
  if (mainPkg.dependencies['expo-glass-effect']) return;
  const ours = readFileSync(join(SRC, '../app.config.ts'), 'utf8');
  const v = (s: string) => /\bversion:\s*'([^']+)'/.exec(s)?.[1];
  expect(v(ours)).toBeDefined();
  expect(v(ours)).not.toBe(v(mainCfg));
});

it('the lock cover is marked exempt (opaque on purpose), not migrated', () => {
  const src = readFileSync(join(SRC, 'components/AppLockGate.tsx'), 'utf8');
  expect(src).not.toMatch(/GlassScreen/);
  expect(src.match(/backgroundColor:\s*c\.bg\b.*glass-exempt/g)?.length).toBe(2);
});
