/**
 * The one host search engines should index: app.fynora.net.
 *
 * Owner decision, 2026-09-24. Checked against production before choosing: fynora.net answers with a
 * 301 to https://app.fynora.net/, and www.fynora.net and app.fynora.net both serve the same site
 * directly, so three hosts showed identical content with no canonical. fynora.net cannot be the
 * canonical while it redirects here (the tag would point at a URL that sends you straight back), and
 * making it canonical would need an infrastructure change first (serve the apex, redirect app.* to
 * it, keep the app-link files and CORS working). Until that is done deliberately, this is the host.
 *
 * Absolute on purpose, not window.location.origin: a canonical must name the preferred host even
 * when the page was reached through www, a preview deploy or dev-app.
 *
 * scripts/prerenderTitle.mjs carries its own copy of this string (a build script cannot import
 * TypeScript from src); seoFiles.test.tsx fails if the two, robots.txt or sitemap.xml disagree.
 */
export const SITE_ORIGIN = 'https://app.fynora.net';

/**
 * True when this build talks to the DEV API: a Cloudflare preview or the `dev` branch alias
 * (dev-app.fynora.net). deployment-guide.md puts VITE_API_BASE_URL=https://dev-api.fynora.net in the
 * Pages Preview bucket, which every non-production build inherits, and production builds use
 * api.fynora.net. Such builds are served with noindex (scripts/crawlPolicy.mjs), and a noindex page
 * must not also name a canonical: Google reads that combination as a conflict.
 */
export function isNonProductionBuild(apiBase: string | undefined = import.meta.env.VITE_API_BASE_URL): boolean {
  return /\/\/dev-api\./.test(apiBase ?? '');
}

/**
 * A page's meta description: its subtitle, minus a leading "Last updated: <Month> <year>." which
 * says nothing about the page. No new copy is written for this; the subtitle is already the page's
 * own reviewed summary. scripts/prerenderTitle.mjs has the same rule for the prerendered HTML, and
 * seoFiles.test.tsx checks the two agree.
 */
export function pageDescription(subtitle: string | undefined): string | null {
  const stripped = (subtitle ?? '').replace(/^Last updated: [A-Za-z]+ \d{4}\.\s*/, '').trim();
  return stripped === '' ? null : stripped;
}

/** Absolute canonical URL for a route path. No trailing slash except for the root, no query. */
export function canonicalUrl(path: string): string {
  const clean = path.split(/[?#]/)[0];
  const trimmed = clean.length > 1 ? clean.replace(/\/+$/, '') : clean;
  return SITE_ORIGIN + (trimmed === '' ? '/' : trimmed);
}
