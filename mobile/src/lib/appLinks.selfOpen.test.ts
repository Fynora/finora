import { readdirSync, readFileSync, statSync } from 'fs';
import path from 'path';
import { isClaimedPath } from './appLinks';

// A path the app claims is answered by the app itself on Android -- including when the app is the
// one opening it. So a page the app deliberately sends to a browser ("Manage on web") must never be
// a claimed path, or that button silently reopens the app instead of reaching the web page. That
// was a real bug in the first version of this feature (/app/billing), found by review, not by any
// test -- this is the test.

const SRC = path.join(__dirname, '..');

// webUrl.ts defines the two functions; its own `webUrl(path)` is the implementation, not a page
// the app opens.
const IMPLEMENTATION = 'lib/webUrl.ts';

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [full] : [];
  });
}

const sources = sourceFiles(SRC)
  .map((file) => ({ file: path.relative(SRC, file), text: readFileSync(file, 'utf8') }))
  .filter(({ file }) => file !== IMPLEMENTATION);

// The two ways a path reaches a browser, each with the path written out where it is used:
//   webUrl('/x') / openWebUrl('/x')        -- a direct call
//   <LegalLink label="..." path="/x" />   -- the shared legal link, which calls openWebUrl(path)
// Kept as separate patterns so each can be shown to match something on its own. When they were
// one alternation, the legal links moved from a `link('Label', '/x')` helper to the LegalLink
// component and that half went on matching nothing for a release, unnoticed, because the other
// half still found /privacy and /terms through Settings.
const DIRECT_CALL = /(?:webUrl|openWebUrl)\(\s*['"`](\/[^'"`?#]*)/g;
const LEGAL_LINK = /<LegalLink\b[^>]*?\bpath=["'](\/[^"'?#]*)/g;

function pathsMatching(pattern: RegExp): Set<string> {
  const found = new Set<string>();
  for (const { text } of sources) {
    for (const match of text.matchAll(pattern)) found.add(match[1]);
  }
  return found;
}

describe('paths the app opens in a browser', () => {
  const direct = pathsMatching(DIRECT_CALL);
  const legal = pathsMatching(LEGAL_LINK);
  const opened = new Set([...direct, ...legal]);

  it('finds the direct call sites (guards that half of the scan against silently matching nothing)', () => {
    expect(direct).toContain('/app/billing');
    // Settings' own Legal rows.
    expect(direct).toContain('/your-data');
  });

  it('finds the shared legal links (guards that half of the scan against silently matching nothing)', () => {
    // /trust reaches a browser through LegalLink alone on the auth screens; the other three are
    // named so that a link quietly dropped from the footer shows up here too.
    expect([...legal]).toEqual(expect.arrayContaining(['/privacy', '/terms', '/trust', '/your-data']));
  });

  it('are never claimed as app links', () => {
    const claimed = [...opened].filter(isClaimedPath);
    expect(claimed).toEqual([]);
  });
});

// The scan above reads paths out of the source text, so it can only vouch for a path that is
// written where it is used. These two close the ways one can slip past it.
describe('every path the app opens in a browser is visible to the scan', () => {
  it('webUrl/openWebUrl are only ever called with a literal path, except inside LegalLink', () => {
    // Counted per file, not per line, so a call wrapped over two lines is not mistaken for a
    // hidden one. Comments count too: write "openWebUrl with a literal path", not a call-shaped
    // example, or this reads it as a call the scan could not follow.
    const ANY_CALL = /\b(?:webUrl|openWebUrl)\(/g;
    const hidden = sources
      .filter(({ text }) => [...text.matchAll(ANY_CALL)].length > [...text.matchAll(DIRECT_CALL)].length)
      .map(({ file }) => file);
    // LegalLink is the one sanctioned pass-through: its paths are read from its call sites instead
    // (the LEGAL_LINK pattern), and the next test holds those to a literal too.
    expect(hidden).toEqual(['components/LegalLink.tsx']);
  });

  it('every LegalLink is given its path as a literal, never a variable', () => {
    const uses = sources.reduce((n, { text }) => n + [...text.matchAll(/<LegalLink\b/g)].length, 0);
    const withLiteralPath = sources.reduce((n, { text }) => n + [...text.matchAll(LEGAL_LINK)].length, 0);
    expect(uses).toBeGreaterThan(0);
    expect(withLiteralPath).toBe(uses);
  });
});
