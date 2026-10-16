import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { it, expect } from 'vitest';

// Every date format here that names its month in a locale other than en-US must pass through
// withSep (utils/date.ts): Intl's en-IN writes September as "Sept", and a new format written
// without it brings that back.
const SRC = join(__dirname, '..');

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

it('every short-month date format goes through withSep', () => {
  const misses: string[] = [];
  for (const path of files(SRC)) {
    const lines = readFileSync(path, 'utf8').split('\n');
    lines.forEach((line, i) => {
      if (!/toLocale(Date)?String\((?!'en-US')/.test(line)) return;
      const call = lines.slice(i, i + 3).join(' ');
      if (/month:\s*'short'/.test(call) && !/withSep\(/.test(line)) misses.push(`${relative(SRC, path)}:${i + 1}`);
    });
  }
  expect(misses).toEqual([]);
});
