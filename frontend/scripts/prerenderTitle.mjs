// Pure helpers for scripts/prerender.mjs (per-page <title> and canonical), split out so they can be
// unit-tested without running a whole build.
//
// Why this exists: prerender.mjs stamps every route's markup into one shared index.html template,
// so every prerendered page (Terms, Privacy, Refunds, ...) shipped the SAME <title>. That is what a
// crawler that does not run JavaScript reads. PublicLayout sets document.title on the client, but
// that never touches these static files, so the title has to be set here as well.

// Must match how PublicLayout.tsx builds document.title: `${title} — Fynora`, except that a title
// which already names Fynora ("About Fynora", "Careers at Fynora") is used as it is, rather than
// repeating the brand ("About Fynora — Fynora"). prerenderTitle.test.tsx checks the two agree.
export const TITLE_SUFFIX = ' — Fynora';

export function fullTitle(title) {
  return /fynora/i.test(title) ? title : title + TITLE_SUFFIX;
}

/**
 * The page title, taken from the <h1> PublicLayout renders from its own `title` prop, so this and
 * the client-side document.title come from the same string and cannot drift apart. React has
 * already HTML-escaped it (`&amp;`), which is exactly what belongs inside <title>.
 * Returns null if there is no plain-text <h1>.
 */
export function pageTitleFromMarkup(appHtml) {
  const match = /<h1[^>]*>([^<]*)<\/h1>/.exec(appHtml);
  if (!match || !match[1].trim()) return null;
  return fullTitle(match[1].trim());
}

/** Replaces the template's <title>. Throws rather than silently keeping the shared one. */
export function withTitle(templateHtml, title) {
  const titleTag = /<title>[\s\S]*?<\/title>/;
  if (!titleTag.test(templateHtml)) {
    throw new Error("prerender: the index.html template has no <title> to replace.");
  }
  // A replacer function, not a string: a title containing `$&` or `$1` must not be interpreted.
  return templateHtml.replace(titleTag, () => `<title>${title}</title>`);
}

// The host search engines should index. A copy of SITE_ORIGIN in src/lib/siteUrl.ts (a build script
// cannot import TypeScript from src); seoFiles.test.tsx fails if the two differ.
export const SITE_ORIGIN = 'https://app.fynora.net';

/**
 * Adds <link rel="canonical"> for a route, just before </head>. The homepage is deliberately not
 * given one here: the prerendered index.html is also what the SPA fallback serves for every route
 * this build does not list, so a canonical in it would mark those pages as duplicates of the home
 * page. Landing sets its own on the client instead (useCanonical).
 */
export function withCanonical(templateHtml, route) {
  if (/rel=["']canonical["']/.test(templateHtml)) {
    throw new Error('prerender: the index.html template already has a canonical link; it must not.');
  }
  if (!templateHtml.includes('</head>')) {
    throw new Error('prerender: the index.html template has no </head> to add a canonical link to.');
  }
  const href = SITE_ORIGIN + route;
  return templateHtml.replace('</head>', () => `<link rel="canonical" href="${href}" />\n</head>`);
}

/**
 * A page's description, taken from the subtitle PublicLayout tags with data-seo="description", minus
 * a leading "Last updated: <Month> <year>.". The same rule as pageDescription() in
 * src/lib/siteUrl.ts (seoFiles.test.tsx checks they agree). Returns null if there is no subtitle.
 */
export function pageDescriptionFromMarkup(appHtml) {
  const match = /<p[^>]*data-seo="description"[^>]*>([^<]*)<\/p>/.exec(appHtml);
  if (!match) return null;
  const stripped = match[1].replace(/^Last updated: [A-Za-z]+ \d{4}\.\s*/, '').trim();
  return stripped === '' ? null : stripped;
}

function setMetaContent(html, attr, key, content) {
  const tag = new RegExp(`(<meta ${attr}="${key}" content=")[^"]*(")`);
  if (!tag.test(html)) {
    throw new Error(`prerender: the index.html template has no <meta ${attr}="${key}"> to replace.`);
  }
  return html.replace(tag, (_all, open, close) => open + content + close);
}

/**
 * Makes the description and the social-preview tags describe this page rather than the homepage,
 * and adds og:url. Social crawlers (Facebook, LinkedIn, WhatsApp, X) do not run JavaScript, so this
 * static HTML is the only place they can read them. `title` and `description` are already
 * HTML-escaped by React, which is what belongs inside an attribute.
 */
export function withPageMeta(templateHtml, { title, description, route }) {
  let out = setMetaContent(templateHtml, 'name', 'description', description);
  out = setMetaContent(out, 'property', 'og:title', title);
  out = setMetaContent(out, 'property', 'og:description', description);
  if (!out.includes('</head>')) throw new Error('prerender: the index.html template has no </head>.');
  return out.replace('</head>', () => `<meta property="og:url" content="${SITE_ORIGIN + route}" />\n</head>`);
}
