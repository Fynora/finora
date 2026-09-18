import { readdirSync, readFileSync, statSync } from 'fs';
import path from 'path';
import { isClaimedPath } from './appLinks';

// A path the app claims is answered by the app itself on Android -- including when the app is the
// one opening it. So a page the app deliberately sends to a browser ("Manage on web") must never be
// a claimed path, or that button silently reopens the app instead of reaching the web page. That
// was a real bug in the first version of this feature (/app/billing), found by review, not by any
// test -- this is the test.

const SRC = path.join(__dirname, '..');

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [full] : [];
  });
}

// webUrl('/x') / openWebUrl('/x'), and LegalFooterLinks' link('Label', '/x').
const CALL_SITE = /(?:webUrl|openWebUrl)\(\s*['"`](\/[^'"`?#]*)|\blink\(\s*['"][^'"]*['"],\s*['"](\/[^'"`?#]*)/g;

function pathsTheAppOpensInABrowser(): Set<string> {
  const found = new Set<string>();
  for (const file of sourceFiles(SRC)) {
    const text = readFileSync(file, 'utf8');
    for (const match of text.matchAll(CALL_SITE)) found.add(match[1] ?? match[2]);
  }
  return found;
}

describe('paths the app opens in a browser', () => {
  const opened = pathsTheAppOpensInABrowser();

  it('finds the call sites at all (guards the scan itself against silently matching nothing)', () => {
    // One from each shape: a bare webUrl() call, and the shared legal links.
    expect(opened).toContain('/app/billing');
    expect(opened).toContain('/privacy');
    expect(opened).toContain('/terms');
  });

  it('are never claimed as app links', () => {
    const claimed = [...opened].filter(isClaimedPath);
    expect(claimed).toEqual([]);
  });
});
