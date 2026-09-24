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

/** Absolute canonical URL for a route path. No trailing slash except for the root, no query. */
export function canonicalUrl(path: string): string {
  const clean = path.split(/[?#]/)[0];
  const trimmed = clean.length > 1 ? clean.replace(/\/+$/, '') : clean;
  return SITE_ORIGIN + (trimmed === '' ? '/' : trimmed);
}
