import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  PRODUCTION_BRANCH,
  applyToDist,
  isNonProduction,
  noindexHeaders,
  noindexHtml,
  robotsForNonProduction,
} from './crawlPolicy.mjs';

describe('isNonProduction', () => {
  it('treats the production branch on Pages as production', () => {
    expect(isNonProduction({ CF_PAGES: '1', CF_PAGES_BRANCH: PRODUCTION_BRANCH, VITE_API_BASE_URL: 'https://api.fynora.net' })).toBe(false);
  });

  it('treats the dev branch and every PR preview branch as non-production', () => {
    expect(isNonProduction({ CF_PAGES: '1', CF_PAGES_BRANCH: 'dev' })).toBe(true);
    expect(isNonProduction({ CF_PAGES: '1', CF_PAGES_BRANCH: 'worktree-landing-claims-seo' })).toBe(true);
  });

  it('treats a build using the dev API as non-production, even without Pages variables', () => {
    expect(isNonProduction({ VITE_API_BASE_URL: 'https://dev-api.fynora.net' })).toBe(true);
  });

  it('treats a build it cannot identify as production, so a missing variable never de-indexes the site', () => {
    expect(isNonProduction({})).toBe(false);
    expect(isNonProduction({ CF_PAGES: '1' })).toBe(false); // on Pages but no branch reported
    expect(isNonProduction({ CF_PAGES_BRANCH: 'dev' })).toBe(false); // not on Pages: a local shell
  });
});

describe('noindexHtml', () => {
  const HTML = '<html><head><title>x</title><link rel="canonical" href="https://app.fynora.net/terms" />\n</head><body></body></html>';

  it('adds the robots meta and removes the canonical', () => {
    const out = noindexHtml(HTML);
    expect(out).toContain('<meta name="robots" content="noindex, nofollow" />');
    expect(out).not.toContain('canonical');
  });

  it('is idempotent', () => {
    const once = noindexHtml(HTML);
    expect(noindexHtml(once)).toBe(once);
  });

  it('refuses a file with no </head> instead of silently leaving it indexable', () => {
    expect(() => noindexHtml('<html></html>')).toThrow(/no <\/head>/);
  });
});

describe('noindexHeaders and robotsForNonProduction', () => {
  it('appends an X-Robots-Tag rule for every path, keeping what was there, once', () => {
    const existing = '/*\n  X-Frame-Options: DENY\n';
    const out = noindexHeaders(existing);
    expect(out.startsWith(existing)).toBe(true);
    expect(out).toContain('/*\n  X-Robots-Tag: noindex, nofollow');
    expect(noindexHeaders(out)).toBe(out);
  });

  it('drops the production Sitemap line but keeps the disallow rules, and never disallows everything', () => {
    const out = robotsForNonProduction('User-agent: *\nDisallow: /app/\nDisallow: /auth\n\nSitemap: https://app.fynora.net/sitemap.xml\n');
    expect(out).not.toMatch(/^Sitemap:/m);
    expect(out).toContain('Disallow: /app/');
    expect(out).not.toMatch(/^Disallow: \/$/m);
  });
});

describe('applyToDist, on a real directory', () => {
  const dirs: string[] = [];
  afterEach(() => {
    for (const d of dirs.splice(0)) fs.rmSync(d, { recursive: true, force: true });
  });

  function makeDist() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'crawlpolicy-'));
    dirs.push(dir);
    fs.writeFileSync(path.join(dir, 'index.html'), '<html><head></head><body>home</body></html>');
    fs.writeFileSync(path.join(dir, 'terms.html'), '<html><head><link rel="canonical" href="https://app.fynora.net/terms" />\n</head></html>');
    fs.writeFileSync(path.join(dir, '_headers'), '/*\n  X-Frame-Options: DENY\n');
    fs.writeFileSync(path.join(dir, 'robots.txt'), 'User-agent: *\nDisallow: /app/\n\nSitemap: https://app.fynora.net/sitemap.xml\n');
    return dir;
  }

  it('makes every HTML file, the headers and robots.txt non-indexable', () => {
    const dir = makeDist();
    expect(applyToDist(dir)).toBe(2);
    for (const f of ['index.html', 'terms.html']) {
      expect(fs.readFileSync(path.join(dir, f), 'utf-8')).toContain('noindex, nofollow');
    }
    expect(fs.readFileSync(path.join(dir, 'terms.html'), 'utf-8')).not.toContain('canonical');
    expect(fs.readFileSync(path.join(dir, '_headers'), 'utf-8')).toContain('X-Robots-Tag: noindex, nofollow');
    expect(fs.readFileSync(path.join(dir, 'robots.txt'), 'utf-8')).not.toContain('Sitemap:');
  });
});
