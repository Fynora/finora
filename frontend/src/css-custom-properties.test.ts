import { readFileSync, readdirSync, statSync } from 'fs';
import { join } from 'path';
import { describe, expect, it } from 'vitest';

/**
 * Guards against the exact bug SiteFooter.tsx shipped: Phase 5 PR1 (#1393) renamed the
 * old --m-surface-deep property to --color-deep-surface and deleted the old definition from
 * index.css, but missed this one call site, which kept referencing the old name. Nothing failed
 * -- the property just silently resolved to nothing, so the footer's background fell back to
 * transparent and its white logo text/icon became invisible against it. No test caught it because
 * none checked that a referenced custom property is still defined anywhere.
 *
 * This scans every CSS custom-property reference in the app (component inline styles and
 * index.css itself) and asserts each name is defined by some declaration in index.css -- in any
 * selector, since a property scoped to .dark or .marketing is still a legitimate definition for a
 * reference used inside that scope.
 */
const SRC = join(__dirname);
const CSS_FILE = join(__dirname, 'index.css');

// This file's own source would otherwise match its own scan pattern.
const ALLOWED = ['css-custom-properties.test.ts'];

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    return /\.(tsx?|css)$/.test(entry) ? [full] : [];
  });
}

function definedCustomProperties(): Set<string> {
  const names = new Set<string>();
  for (const line of readFileSync(CSS_FILE, 'utf8').split('\n')) {
    const m = /^\s*(--[a-zA-Z0-9-]+)\s*:/.exec(line);
    if (m) names.add(m[1]);
  }
  return names;
}

const REFERENCE_PATTERN = /var\((--[a-zA-Z0-9-]+)\)/g;

// Block comments -- especially index.css's own doc comments about the custom-property system --
// legitimately use this syntax to describe it generically (e.g. as a placeholder name), which
// isn't a real reference. Replacing comment characters with spaces (not deleting them) keeps every
// line number accurate for the offender messages below.
function stripBlockComments(content: string): string {
  return content.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

describe('CSS custom properties are defined where they are referenced', () => {
  it('finds source files to check', () => {
    expect(sourceFiles(SRC).length).toBeGreaterThan(20);
  });

  it('has no reference to a custom property missing from index.css', () => {
    const defined = definedCustomProperties();
    expect(defined.size).toBeGreaterThan(20);

    const offenders: string[] = [];
    for (const file of sourceFiles(SRC)) {
      const rel = file.replace(SRC, '').replace(/\\/g, '/').replace(/^\//, '');
      if (ALLOWED.some((a) => rel === a)) continue;

      stripBlockComments(readFileSync(file, 'utf8')).split('\n').forEach((line, i) => {
        for (const m of line.matchAll(REFERENCE_PATTERN)) {
          if (!defined.has(m[1])) offenders.push(`${rel}:${i + 1}  ${m[1]}`);
        }
      });
    }

    expect(
      offenders,
      'These custom properties are referenced but never defined in index.css -- they silently ' +
        'resolve to nothing (transparent background, unset color) instead of failing loudly. ' +
        'Likely a stale reference left behind by a token rename.'
    ).toEqual([]);
  });
});
