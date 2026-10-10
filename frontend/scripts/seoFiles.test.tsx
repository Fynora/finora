import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { SITE_ORIGIN, canonicalUrl } from '../src/lib/siteUrl';
import {
  SITE_ORIGIN as SCRIPT_ORIGIN,
  templateForHomepage,
  templateForNotFound,
  templateForPage,
  withCanonical,
  withRobotsNoindex,
} from './prerenderTitle.mjs';
import { spaShellHtml } from './spaShell.mjs';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const read = (rel: string) => fs.readFileSync(path.join(root, rel), 'utf-8');

const robots = read('public/robots.txt');
const sitemap = read('public/sitemap.xml');
const appSource = read('src/App.tsx');

const disallowRules = robots
  .split('\n')
  .filter((l) => /^Disallow:/i.test(l))
  .map((l) => l.replace(/^Disallow:\s*/i, '').trim());

/** Google's matching: a rule is a path prefix, and a trailing `$` anchors it to the whole path. */
function isDisallowed(p: string): boolean {
  return disallowRules.some((rule) => (rule.endsWith('$') ? p === rule.slice(0, -1) : p.startsWith(rule)));
}

const sitemapPaths = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1].replace(SITE_ORIGIN, ''));
const routePaths = [...appSource.matchAll(/<Route path="([^"]+)"/g)].map((m) => m[1]).filter((p) => p !== '*');

describe('the one indexed host', () => {
  it('is the same in the app and in the build script', () => {
    expect(SCRIPT_ORIGIN).toBe(SITE_ORIGIN);
  });

  it('is app.fynora.net, where the site is served (fynora.net 301s to it)', () => {
    expect(SITE_ORIGIN).toBe('https://app.fynora.net');
  });
});

describe('canonicalUrl', () => {
  it('is absolute on the indexed host, without trailing slash or query', () => {
    expect(canonicalUrl('/')).toBe('https://app.fynora.net/');
    expect(canonicalUrl('/terms')).toBe('https://app.fynora.net/terms');
    expect(canonicalUrl('/terms/')).toBe('https://app.fynora.net/terms');
    expect(canonicalUrl('/help?topic=billing#x')).toBe('https://app.fynora.net/help');
  });

  it('names the lower-case route for a case variant, which React Router renders as the same page', () => {
    expect(canonicalUrl('/About')).toBe('https://app.fynora.net/about');
    expect(canonicalUrl('/REFUND-POLICY/')).toBe('https://app.fynora.net/refund-policy');
  });
});

describe('robots.txt', () => {
  it('points at the sitemap on the indexed host', () => {
    expect(robots).toContain(`Sitemap: ${SITE_ORIGIN}/sitemap.xml`);
  });

  it('keeps crawlers out of the signed-in app and every auth flow, including the token URLs', () => {
    for (const p of ['/app', '/app/billing', '/app/imports/123', '/auth', '/login', '/register',
      '/forgot-password', '/reset-password', '/verify-email', '/verify-phone', '/email-change-verify']) {
      expect(isDisallowed(p), `${p} should be disallowed`).toBe(true);
    }
  });

  it('does not block the public pages, or /app-prefixed lookalikes like the app-link files', () => {
    // /og-image.png is what link previews show; a crawler barred from it shows no preview at all.
    for (const p of ['/', '/terms', '/help', '/trust', '/your-data', '/og-image.png', '/favicon.png', '/.well-known/apple-app-site-association']) {
      expect(isDisallowed(p), `${p} should be allowed`).toBe(false);
    }
  });
});

describe('sitemap.xml', () => {
  it('lists only URLs on the indexed host, once each', () => {
    const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
    expect(locs.length).toBeGreaterThan(0);
    for (const loc of locs) expect(loc.startsWith(SITE_ORIGIN + '/'), loc).toBe(true);
    expect(new Set(locs).size).toBe(locs.length);
  });

  it('lists only real routes that robots.txt lets crawlers fetch', () => {
    for (const p of sitemapPaths) {
      expect(routePaths, `${p} is in the sitemap but is not a route in App.tsx`).toContain(p);
      expect(isDisallowed(p), `${p} is in the sitemap but disallowed by robots.txt`).toBe(false);
    }
  });

  it('forces a decision for every route: each is either in the sitemap or disallowed', () => {
    // A new public route must be added to the sitemap; a new private one to robots.txt. Redirects
    // (/login, /register) are disallowed already. Failing here is the point.
    for (const p of routePaths) {
      const listed = sitemapPaths.includes(p);
      expect(listed || isDisallowed(p), `${p} is neither in sitemap.xml nor disallowed in robots.txt`).toBe(true);
    }
  });

  it('is served as its own prerendered page, never the homepage fallback', () => {
    // /trust, /your-data and /cookie-policy were in the sitemap but not prerendered, so a crawler
    // that does not run JavaScript was handed the HOMEPAGE (title, h1 and all) at each of them.
    const ssrRoutes = [...read('scripts/ssr-entry.tsx').matchAll(/^\s*'(\/[^']*)':\s*page\(/gm)].map((m) => m[1]);
    const outputFiles = [...read('scripts/prerender.mjs').matchAll(/^\s*'(\/[^']*)':\s*'[^']+\.html',/gm)].map((m) => m[1]);
    for (const p of sitemapPaths) {
      expect(ssrRoutes, `${p} is in the sitemap but scripts/ssr-entry.tsx does not render it`).toContain(p);
      expect(outputFiles, `${p} is in the sitemap but scripts/prerender.mjs does not write it`).toContain(p);
    }
  });

  it('never lists the not-found page, which the build writes to dist/404.html outside the route table', () => {
    const ssr = read('scripts/ssr-entry.tsx');
    expect(ssr).toMatch(/export const notFoundPage[^\n]*page\('\/404', NotFound\)/);
    expect(read('scripts/prerender.mjs')).toContain("const NOT_FOUND_FILE = '404.html';");
    expect(sitemapPaths).not.toContain('/404');
    // Not in `routes` either, or the "includes every page the build prerenders" test below would
    // demand it in the sitemap.
    expect(ssr).not.toMatch(/^\s*'\/404':/m);
  });

  it('includes every page the build prerenders', () => {
    const ssr = read('scripts/ssr-entry.tsx');
    const prerendered = [...ssr.matchAll(/^\s*'(\/[^']*)':\s*page\(/gm)].map((m) => m[1]);
    expect(prerendered.length).toBeGreaterThan(0);
    for (const p of prerendered) expect(sitemapPaths, p).toContain(p);
  });
});

describe('canonical in the built HTML', () => {
  const TEMPLATE = '<html><head><title>x</title></head><body><div id="root"></div></body></html>';

  it('adds an absolute canonical for the route just before </head>', () => {
    const out = withCanonical(TEMPLATE, '/terms');
    expect(out).toContain('<link rel="canonical" href="https://app.fynora.net/terms" />\n</head>');
  });

  it('refuses a template that already has one, and one with no </head>', () => {
    expect(() => withCanonical(TEMPLATE.replace('</head>', '<link rel="canonical" href="/" /></head>'), '/terms'))
      .toThrow(/already has a canonical/);
    expect(() => withCanonical('<html></html>', '/terms')).toThrow(/no <\/head>/);
  });

  it('is never added to the not-found page: withRobotsNoindex and withCanonical do not meet', () => {
    const out = withRobotsNoindex(TEMPLATE);
    expect(out).toContain('<meta name="robots" content="noindex" />\n</head>');
    expect(out).not.toMatch(/canonical/);
    expect(() => withRobotsNoindex(out)).toThrow(/already has a robots meta/);
    expect(() => withRobotsNoindex('<html></html>')).toThrow(/no <\/head>/);
  });

  it('is absent from the source index.html: it is the template for every other document the build writes', () => {
    // withCanonical refuses a template that already has one (above), and the blank shell and the
    // not-found page, both built from it, must have none at all. The homepage's own canonical is
    // added to dist/index.html by the build, which the next block holds.
    expect(read('index.html')).not.toMatch(/rel=["']canonical["']/);
  });
});

// A crawler that does not run JavaScript reads only these static tags. Until the build had a
// 404.html the homepage could not have them: production answered every path without a file with
// index.html, so a canonical in it named the homepage as the address of other pages. Now
// index.html is served at "/" alone, and each document says where it lives, or says nothing.
describe('the address each built document names', () => {
  // The committed template. The build's is Vite's output of this same file (hashed script and
  // stylesheet tags added), which touches none of the tags counted here.
  const template = read('index.html');
  const prerenderSource = read('scripts/prerender.mjs');
  const outputRoutes = [...prerenderSource.matchAll(/^\s*'(\/[^']*)':\s*'[^']+\.html',/gm)].map((m) => m[1]);
  const FONT = '/assets/manrope-latin-800-normal-x.woff2';

  const hrefs = (html: string, tag: RegExp, attr: string) =>
    (html.match(tag) ?? []).map((t) => new RegExp(`${attr}="([^"]*)"`).exec(t)?.[1]);
  const canonicals = (html: string) => hrefs(html, /<link\b[^>]*rel=["']canonical["'][^>]*>/g, 'href');
  const ogUrls = (html: string) => hrefs(html, /<meta\b[^>]*property=["']og:url["'][^>]*>/g, 'content');
  const titleOf = (html: string) => /<title>([^<]*)<\/title>/.exec(html)?.[1];
  const descriptionOf = (html: string) => /<meta name="description" content="([^"]*)"/.exec(html)?.[1];

  it('homepage (dist/index.html): exactly one canonical and one og:url, the address the browser also sets', () => {
    const home = templateForHomepage(template, FONT);
    expect(canonicals(home)).toEqual(['https://app.fynora.net/']);
    expect(ogUrls(home)).toEqual(['https://app.fynora.net/']);
    // Landing calls useCanonical('/'), which sets the tag it finds to canonicalUrl('/'). If the two
    // strings differed, a crawler that runs JavaScript and one that does not would be told
    // different addresses.
    expect(canonicals(home)[0]).toBe(canonicalUrl('/'));
    // Only address tags and the font preload are added: the title and description stay the
    // template's, and the root stays empty for the markup.
    expect(titleOf(home)).toBe(titleOf(template));
    expect(descriptionOf(home)).toBe(descriptionOf(template));
    expect(home).toContain(`<link rel="preload" href="${FONT}" as="font"`);
    expect(home).toContain('<div id="root"></div>');
  });

  it('every other prerendered page: exactly one of each, for its own route', () => {
    const pages = outputRoutes.filter((route) => route !== '/');
    expect(pages.length).toBeGreaterThan(0);
    expect(outputRoutes).toContain('/');
    for (const route of pages) {
      const page = templateForPage(template, { title: 'T — Fynora', description: 'D.', route });
      expect(canonicals(page), route).toEqual([SITE_ORIGIN + route]);
      expect(ogUrls(page), route).toEqual([SITE_ORIGIN + route]);
      expect(canonicals(page)[0], route).toBe(canonicalUrl(route));
    }
  });

  it('blank shell (dist/spa-shell.html) and not-found page (dist/404.html): neither tag, and noindex', () => {
    const shell = spaShellHtml(template);
    const notFound = templateForNotFound(template, { title: 'Page not found — Fynora', description: 'Missing.' });
    for (const [name, doc] of [['spa-shell.html', shell], ['404.html', notFound]] as const) {
      expect(canonicals(doc), name).toEqual([]);
      expect(ogUrls(doc), name).toEqual([]);
      expect(doc, name).toMatch(/<meta name="robots" content="noindex[^"]*" \/>/);
    }
  });

  it('refuses to build the homepage from a template that already names an address', () => {
    // The guard that keeps the tags out of the source index.html: put one there and the build of
    // every page, the homepage first, stops instead of shipping two.
    const withTag = (tag: string) => template.replace('</head>', `${tag}\n</head>`);
    expect(() => templateForHomepage(withTag('<link rel="canonical" href="https://app.fynora.net/" />'), FONT))
      .toThrow(/already has a canonical/);
    expect(() => templateForHomepage(withTag('<meta property="og:url" content="https://app.fynora.net/" />'), FONT))
      .toThrow(/already has an og:url/);
    expect(() => templateForPage(withTag('<meta property="og:url" content="https://app.fynora.net/" />'), { title: 'T', description: 'D', route: '/terms' }))
      .toThrow(/already has an og:url/);
  });

  it('is what prerender.mjs writes: every document from the untouched template, through these builders', () => {
    // `template` is read once, before any page is rendered into it. A document built from an
    // already-tagged copy instead (the loop's `pageTemplate`) would carry another page's address.
    expect(prerenderSource).toContain('fs.writeFileSync(path.join(distDir, SPA_SHELL_FILE), spaShellHtml(template));');
    expect(prerenderSource).toContain('pageTemplate = templateForHomepage(template, heroFontHref);');
    expect(prerenderSource).toContain('pageTemplate = templateForPage(template, { title, description, route });');
    expect(prerenderSource).toContain('const pageTemplate = templateForNotFound(template, { title, description });');
    // No address tag is added anywhere else in the script.
    expect(prerenderSource).not.toMatch(/\bwith(Canonical|OgUrl|PageMeta)\(/);
  });
});
