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
//
// This file is the only thing between a forgotten route and an outage for it. The build has a
// top-level 404.html, so on Cloudflare Pages a path with no file and no rule in public/_redirects
// returns HTTP 404 with the not-found page (measured on a Pages preview; see
// docs/operations/deployment/deployment-guide.md, "Which document a path gets"). The routes are
// read from the SOURCE, not from a rendered app, so one behind a build-time flag that is currently
// off (/app/settings/gmail/review) is held to the same rule as the rest.
const prerenderSource = read('scripts/prerender.mjs');
const routeTags = appSource.match(/<Route\b/g) ?? [];
const routePaths = [...appSource.matchAll(/<Route path="([^"]+)"/g)].map((m) => m[1]).filter((p) => p !== '*');
const prerenderedPaths = [...prerenderSource.matchAll(/^\s*'(\/[^']*)':\s*'[^']+\.html',?\s*$/gm)].map((m) => m[1]);
const notFoundFile = prerenderSource.match(/^const NOT_FOUND_FILE = '([^']+)';$/m)?.[1];

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

  it('reads every <Route> in App.tsx: each has a literal, absolute path this file can check', () => {
    // A route written as path={SOMETHING}, as a relative child path, or as an index route would be
    // invisible to the regex above, and so to the rule below that keeps it from returning 404.
    // If this fails, teach this file to read the new shape before adding the route.
    expect(routePaths.length + 1, 'every <Route> but the "*" catch-all').toBe(routeTags.length);
    expect(appSource.match(/<Route path="\*"/g)).toHaveLength(1);
    for (const route of routePaths) expect(route.startsWith('/'), route).toBe(true);
    expect(new Set(routePaths).size).toBe(routePaths.length);
  });

  it('includes a route that only exists behind a build flag', () => {
    expect(appSource).toMatch(/\{GMAIL_SYNC_UI_ENABLED && \(\s*<Route path="\/app\/settings\/gmail\/review"/);
    expect(routePaths).toContain('/app/settings/gmail/review');
  });

  it('finds the not-found page, the file that makes a missed route a 404', () => {
    // Top-level and named exactly 404.html: that name is what Cloudflare Pages looks for.
    expect(notFoundFile).toBe('404.html');
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
        expect(
          matchRedirect(rules, address),
          `${address} has no file and no rule in public/_redirects: Cloudflare Pages would answer it with 404.html and HTTP 404`,
        ).toBeDefined();
      }
    }
  });

  it('gives every prerendered page a route, so no file is served for an address the app does not know', () => {
    for (const page of prerenderedPaths) expect(routePaths, page).toContain(page);
  });

  it('has no exact rule left over for a route that is gone', () => {
    // A stale rule is a soft 404: the address would get the shell with a 200, and React would then
    // show the not-found page. (An unknown address UNDER /app does exactly that, because of the
    // splat. Accepted: robots.txt disallows /app and the shell is noindex.)
    for (const rule of rules.filter((r) => !isDynamicRule(r))) {
      const route = rule.from.length > 1 ? rule.from.replace(/\/$/, '') : rule.from;
      expect(routePaths, `${rule.from} (line ${rule.lineNumber}) names no route in App.tsx`).toContain(route);
    }
  });

  it('only names addresses robots.txt already keeps crawlers out of, because the shell is noindex', () => {
    for (const rule of rules) {
      const address = rule.from.replace(/\*$/, 'x');
      expect(isDisallowed(address), `${rule.from} is crawlable, and would be served a noindex page`).toBe(true);
    }
  });

  it('leaves unknown paths, the not-found page and build assets alone', () => {
    // An unknown path must reach 404.html and its 404 status, not be handed the shell with a 200.
    expect(matchRedirect(rules, '/no-such-page')).toBeUndefined();
    expect(matchRedirect(rules, '/404')).toBeUndefined();
    expect(matchRedirect(rules, '/404.html')).toBeUndefined();
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
