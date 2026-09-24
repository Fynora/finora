import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { SITE_ORIGIN, canonicalUrl } from '../src/lib/siteUrl';
import { SITE_ORIGIN as SCRIPT_ORIGIN, withCanonical } from './prerenderTitle.mjs';

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
    for (const p of ['/', '/terms', '/help', '/trust', '/your-data', '/.well-known/apple-app-site-association']) {
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

  it('is absent from index.html: it is the SPA fallback for every unlisted route', () => {
    // A canonical here would mark /cookie-policy, /trust and /your-data as duplicates of the home page.
    expect(read('index.html')).not.toMatch(/rel=["']canonical["']/);
  });
});
