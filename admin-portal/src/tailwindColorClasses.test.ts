import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import postcss from 'postcss';
import tailwindcss from 'tailwindcss';

/**
 * Tailwind generates nothing at all for a colour class it cannot resolve -- no warning, no build
 * error. The held-statement Approve button shipped as `bg-accent ... text-white` when this app has
 * never had an `accent` colour, so it rendered as white text on a transparent background:
 * invisible. The same silence hid opacity modifiers on hex-valued tokens (`border-success/20`),
 * which produce no rule unless the token can take an alpha value.
 *
 * So rather than keeping a list of valid colour names, this asks Tailwind itself: every
 * colour-utility-shaped class in the source is compiled against the real config, and any class
 * that produces no CSS rule fails the test.
 */
const SRC = dirname(fileURLToPath(import.meta.url));
const ROOT = join(SRC, '..');
const SELF = 'tailwindColorClasses.test.ts';

const COLOR_UTILITY =
  /^(?:[a-z0-9-]+:)*(?:bg|text|border(?:-[xytrblse])?|ring(?:-offset)?|divide|outline|decoration|fill|stroke|from|via|to|placeholder|caret|accent|shadow)-[a-z][a-z0-9-]*(?:\/\d+)?$/;

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    // Test files are skipped: they never render to an operator, and their prose (test names like
    // "divide-by-zero") would otherwise read as class names.
    return /\.tsx?$/.test(entry) && !/\.test\.tsx?$/.test(entry) && entry !== SELF ? [full] : [];
  });
}

function stripComments(source: string): string {
  // Comments may name a dead class to explain a fix (SupportTickets.tsx does); only code counts.
  // Block comments keep their newlines so reported line numbers still match the file.
  return source.replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, '')).replace(/(^|[^:])\/\/.*$/gm, '$1');
}

function colorClassCandidates(): Map<string, string> {
  const firstSeenIn = new Map<string, string>();
  for (const file of sourceFiles(SRC)) {
    for (const token of stripComments(readFileSync(file, 'utf8')).split(/[\s'"`{}()<>,;=]+/)) {
      if (COLOR_UTILITY.test(token) && !firstSeenIn.has(token)) {
        firstSeenIn.set(token, file.slice(SRC.length + 1));
      }
    }
  }
  return firstSeenIn;
}

async function generatedClassNames(candidates: string[]): Promise<Set<string>> {
  const configUrl = pathToFileURL(join(ROOT, 'tailwind.config.js')).href;
  const config = (await import(/* @vite-ignore */ configUrl)).default;
  const result = await postcss([
    tailwindcss({ ...config, content: [{ raw: candidates.join(' '), extension: 'html' }] }),
  ]).process('@tailwind utilities;', { from: undefined });

  const names = new Set<string>();
  result.root.walkRules((rule) => {
    for (const match of rule.selector.matchAll(/\.((?:\\.|[\w-])+)/g)) {
      names.add(match[1].replace(/\\(.)/g, '$1'));
    }
  });
  return names;
}

describe('Tailwind colour classes', () => {
  it('finds the colour classes it is meant to check (guards the scan itself)', () => {
    const candidates = colorClassCandidates();
    expect(candidates.has('bg-primary')).toBe(true);
    expect(candidates.has('text-on-primary')).toBe(true);
    expect(candidates.has('hover:bg-primary-dark')).toBe(true);
  });

  it('flags a class whose colour is not defined (guards the check itself)', async () => {
    const generated = await generatedClassNames(['bg-accent', 'bg-primary', 'border-success/20']);
    expect(generated.has('bg-primary')).toBe(true);
    expect(generated.has('border-success/20')).toBe(true);
    expect(generated.has('bg-accent')).toBe(false);
  });

  it('every colour class in the source compiles to a real CSS rule', async () => {
    const candidates = colorClassCandidates();
    const generated = await generatedClassNames([...candidates.keys()]);
    const dead = [...candidates]
      .filter(([cls]) => !generated.has(cls))
      .map(([cls, file]) => `${cls} (${file})`);

    expect(dead).toEqual([]);
  });

  it('never puts white text on a primary or ink surface, which is near-white in dark mode', () => {
    // Dark mode flips primary and ink to light paper, so `text-white` on them is close to
    // invisible; text-on-primary flips with them. Checked per line because a conditional class
    // (ConfirmDialog's danger/primary switch) can put the two in different string literals; a
    // literal that carries its own non-primary background (`'bg-danger text-white'`) is set aside.
    const ownBackground = /(['"`])[^'"`]*\bbg-(?!primary\b|ink\b)[a-z][^'"`]*\1/g;
    const offenders = sourceFiles(SRC).flatMap((file) =>
      stripComments(readFileSync(file, 'utf8'))
        .split('\n')
        .map((text, i) => ({ text, where: `${file.slice(SRC.length + 1)}:${i + 1}` }))
        .filter(({ text }) => {
          const rest = text.replace(ownBackground, '');
          return /\btext-white\b/.test(rest) && /\bbg-(?:primary|ink)\b(?![-/])/.test(rest);
        })
        .map(({ where, text }) => `${where}: ${text.trim().slice(0, 120)}`)
    );

    expect(offenders).toEqual([]);
  });
});
