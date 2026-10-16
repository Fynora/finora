import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, it, expect } from 'vitest';
import { withSep } from './withSep';

// Intl's en-GB (and en-IN, which a browser can report as its own locale) writes September as
// "Sept", where the server's emails and every other month in the app use three letters.
describe('withSep', () => {
  it.each([
    ['30 Sept 2026', '30 Sep 2026'],
    ['30 Sept 2026, 10:15:00', '30 Sep 2026, 10:15:00'],
    ['Sept 30', 'Sep 30'],
    ['30 Sep 2026', '30 Sep 2026'],
    ['30 September 2026', '30 September 2026'],
  ])('%j reads %j', (input, expected) => {
    expect(withSep(input)).toBe(expected);
  });

  it('turns what en-GB writes for a September day into Sep', () => {
    const raw = new Date(2026, 8, 30).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
    expect(withSep(raw)).toBe('30 Sep 2026');
  });
});

// Every date format here that names its month in a locale other than en-US, or in the browser's
// own locale, must pass through withSep: a new one written without it brings "Sept" back.
describe('every short-month date format goes through withSep', () => {
  const SRC = join(__dirname, '..');
  function files(dir: string): string[] {
    return readdirSync(dir).flatMap((name) => {
      const path = join(dir, name);
      if (statSync(path).isDirectory()) return files(path);
      return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
    });
  }

  it('finds none that skips it', () => {
    const misses: string[] = [];
    for (const path of files(SRC)) {
      const lines = readFileSync(path, 'utf8').split('\n');
      lines.forEach((line, i) => {
        if (!/toLocale(Date)?String\((?!'en-US')/.test(line)) return;
        const call = lines.slice(i, i + 3).join(' ');
        if (/month:\s*'short'/.test(call) && !/withSep\(/.test(line)) {
          misses.push(`${relative(SRC, path)}:${i + 1}`);
        }
      });
    }
    expect(misses).toEqual([]);
  });
});
