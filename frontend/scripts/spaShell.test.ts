import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  SPA_SHELL_FILE,
  SPA_SHELL_PATH,
  isDynamicRule,
  matchRedirect,
  parseRedirects,
  spaShellHtml,
} from './spaShell.mjs';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const read = (rel: string) => fs.readFileSync(path.join(root, rel), 'utf-8');

const rules = parseRedirects(read('public/_redirects'));
const appSource = read('src/App.tsx');
const robots = read('public/robots.txt');

// Read from the sources, the way seoFiles.test.tsx reads App.tsx, so a route or a prerendered page
// added later is checked here without anyone remembering to update a second list.
const routePaths = [...appSource.matchAll(/<Route path="([^"]+)"/g)].map((m) => m[1]).filter((p) => p !== '*');
const prerenderedPaths = [...read('scripts/prerender.mjs').matchAll(/^\s*'(\/[^']*)':\s*'[^']+\.html',?\s*$/gm)].map((m) => m[1]);

const disallowRules = robots
  .split('\n')
  .filter((l) => /^Disallow:/i.test(l))
  .map((l) => l.replace(/^Disallow:\s*/i, '').trim());

function isDisallowed(p: string): boolean {
  return disallowRules.some((rule) => (rule.endsWith('$') ? p === rule.slice(0, -1) : p.startsWith(rule)));
}

/** A concrete address for a route pattern: `/app/imports/:jobId` -> `/app/imports/x`. */
const concrete = (route: string) => route.replace(/:[A-Za-z]\w*/g, 'x');

describe('the lists this file reads', () => {
  it('finds the routes and the prerendered pages (a changed source shape must not empty them)', () => {
    expect(routePaths).toContain('/auth');
    expect(routePaths).toContain('/app/imports/:jobId');
    expect(prerenderedPaths).toContain('/');
    expect(prerenderedPaths).toContain('/privacy');
    expect(prerenderedPaths.length).toBeGreaterThanOrEqual(12);
  });
});

describe('public/_redirects', () => {
  it('only rewrites to the blank shell, with a 200', () => {
    expect(rules.length).toBeGreaterThan(0);
    for (const rule of rules) {
      expect(rule.status, `line ${rule.lineNumber}`).toBe(200);
      // Not SPA_SHELL_FILE: Pages answers an .html destination with a 308, not the file.
      expect(rule.to, `line ${rule.lineNumber}`).toBe(SPA_SHELL_PATH);
    }
    expect(SPA_SHELL_FILE).toBe(`${SPA_SHELL_PATH.slice(1)}.html`);
  });

  it('keeps exact paths above the splat rules, and has no duplicate source', () => {
    const firstDynamic = rules.findIndex(isDynamicRule);
    const lastStatic = rules.findLastIndex((rule) => !isDynamicRule(rule));
    if (firstDynamic !== -1) expect(lastStatic).toBeLessThan(firstDynamic);
    expect(new Set(rules.map((r) => r.from)).size).toBe(rules.length);
  });

  it('never matches the homepage or a prerendered page, with or without a trailing slash', () => {
    for (const page of prerenderedPaths) {
      const forms = page === '/' ? ['/', '/index', '/index.html'] : [page, `${page}/`, `${page}.html`];
      for (const form of forms) {
        expect(matchRedirect(rules, form), `${form} must be served from its own file`).toBeUndefined();
      }
    }
  });

  it('sends every route that is not prerendered to the shell, with or without a trailing slash', () => {
    const browserOnly = routePaths.filter((p) => !prerenderedPaths.includes(p));
    expect(browserOnly.length).toBeGreaterThan(0);
    for (const route of browserOnly) {
      for (const address of [concrete(route), `${concrete(route)}/`]) {
        expect(matchRedirect(rules, address), `${address} would paint the homepage first`).toBeDefined();
      }
    }
  });

  it('only names addresses robots.txt already keeps crawlers out of, because the shell is noindex', () => {
    for (const rule of rules) {
      const address = rule.from.replace(/\*$/, 'x');
      expect(isDisallowed(address), `${rule.from} is crawlable, and would be served a noindex page`).toBe(true);
    }
  });

  it('leaves unknown paths and build assets alone', () => {
    expect(matchRedirect(rules, '/no-such-page')).toBeUndefined();
    expect(matchRedirect(rules, '/assets/index-abc123.js')).toBeUndefined();
    expect(matchRedirect(rules, '/robots.txt')).toBeUndefined();
    expect(matchRedirect(rules, '/.well-known/apple-app-site-association')).toBeUndefined();
    expect(matchRedirect(rules, SPA_SHELL_PATH)).toBeUndefined();
    // /application is not under /app.
    expect(matchRedirect(rules, '/application')).toBeUndefined();
  });
});

describe('parseRedirects and matchRedirect', () => {
  const parsed = parseRedirects('# a comment\n\n/a /x 200\n/b /y   # trailing comment\n/c/* /x 200\n/d/:id /x 200\n');

  it('skips comments and blank lines, and defaults the status to 302', () => {
    expect(parsed.map((r) => [r.from, r.to, r.status])).toEqual([
      ['/a', '/x', 200],
      ['/b', '/y', 302],
      ['/c/*', '/x', 200],
      ['/d/:id', '/x', 200],
    ]);
    expect(parsed[0].lineNumber).toBe(3);
  });

  it('matches exact paths exactly, splats greedily and placeholders within one segment', () => {
    expect(matchRedirect(parsed, '/a')?.from).toBe('/a');
    expect(matchRedirect(parsed, '/a/')).toBeUndefined();
    expect(matchRedirect(parsed, '/c')).toBeUndefined();
    expect(matchRedirect(parsed, '/c/')?.from).toBe('/c/*');
    expect(matchRedirect(parsed, '/c/d/e')?.from).toBe('/c/*');
    expect(matchRedirect(parsed, '/d/1')?.from).toBe('/d/:id');
    expect(matchRedirect(parsed, '/d/1/2')).toBeUndefined();
  });
});

describe('spaShellHtml', () => {
  const template = '<html><head><title>t</title>\n<link rel="canonical" href="https://app.fynora.net/" />\n</head><body><div id="root"></div><script type="module" src="/assets/index-abc.js"></script></body></html>';

  it('keeps the document and its empty root, so the bundle still mounts', () => {
    const shell = spaShellHtml(template);
    expect(shell).toContain('<div id="root"></div>');
    expect(shell).toContain('<script type="module" src="/assets/index-abc.js"></script>');
  });

  it('is noindex and names no canonical', () => {
    const shell = spaShellHtml(template);
    expect(shell).toContain('<meta name="robots" content="noindex, nofollow" />');
    expect(shell).not.toContain('rel="canonical"');
  });

  it('refuses a template that already has a page rendered into it', () => {
    expect(() => spaShellHtml(template.replace('<div id="root"></div>', '<div id="root"><h1>Home</h1></div>'))).toThrow(/before prerendering/);
  });
});
