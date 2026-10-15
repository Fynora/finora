import { readdirSync, readFileSync } from 'fs';
import { join } from 'path';

/**
 * Every number a placeholder draws with lives in theme/skeleton.ts (skeletonTokens), drawn from
 * the existing theme. A file in this folder that writes its own colour, corner radius, height or
 * width would drift from the screens it stands in for without anyone noticing. Percent widths are
 * strings and stay allowed: they describe a text line's share of a row, not a size.
 */
const DIR = __dirname;
const files = readdirSync(DIR).filter((f) => /\.tsx$/.test(f) && !/\.test\.tsx$/.test(f));

const BANNED: { what: string; re: RegExp }[] = [
  { what: 'a colour literal', re: /#[0-9a-fA-F]{3,8}\b|\brgba?\(/ },
  { what: 'a numeric corner radius', re: /borderRadius(=\{|:)\s*\d/ },
  { what: 'a numeric height', re: /\bheight(=\{|:)\s*\d/ },
  { what: 'a numeric width', re: /\bwidth(=\{|:)\s*\d/ },
];

it('the scan sees the skeleton files', () => {
  expect(files).toEqual(expect.arrayContaining(['Shimmer.tsx', 'Skeletons.tsx']));
});

it('no skeleton file writes its own colour, radius, height or width', () => {
  const hits = files.flatMap((f) =>
    readFileSync(join(DIR, f), 'utf8').split('\n').flatMap((line, i) =>
      BANNED.filter(({ re }) => re.test(line)).map(({ what }) => `${f}:${i + 1}: ${what}: ${line.trim()}`)
    )
  );
  expect(hits).toEqual([]);
});
