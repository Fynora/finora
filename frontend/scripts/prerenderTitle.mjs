// Pure helpers for scripts/prerender.mjs (per-page <title>, canonical and social tags), split out so
// they can be unit-tested without running a whole build.
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
  const heading = pageHeadingFromMarkup(appHtml);
  return heading === null ? null : fullTitle(heading);
}

/** The page's <h1> text as React escaped it ("&amp;"), or null if there is no plain-text <h1>. */
export function pageHeadingFromMarkup(appHtml) {
  const match = /<h1[^>]*>([^<]*)<\/h1>/.exec(appHtml);
  if (!match || !match[1].trim()) return null;
  return match[1].trim();
}

/**
 * The plain text behind React's escaping, for values that go into JSON rather than HTML. Only the
 * entities React emits (react-dom escapes exactly &, <, >, " and '). `&amp;` is decoded last so
 * "&amp;lt;" becomes "&lt;", not "<".
 */
export function decodeEntities(html) {
  return html
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#x27;/g, "'")
    .replace(/&amp;/g, '&');
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
 * Adds <link rel="canonical"> for a route, just before </head>. Called once per written page, the
 * homepage included (templateForHomepage below), and never on the template itself: the blank shell
 * and the not-found page are built from the same template and must name no address.
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
  // A page that names its own description (PublicLayout's `description` prop, rendered as a data
  // attribute on the heading section) wins over its subtitle. Same precedence as the component.
  const explicit = /\sdata-seo-description="([^"]*)"/.exec(appHtml);
  if (explicit && explicit[1].trim() !== '') return explicit[1].trim();
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
  // `route: null` is the not-found page: it has no address of its own (it is reached at whatever was
  // typed), so it names none, the same reason it has no canonical.
  if (route === null) return out;
  return withOgUrl(out, route);
}

/**
 * Adds og:url for a route, just before </head>. Throws if the template already has one, for the
 * same reason withCanonical does: every document is built from one shared template, so a tag
 * already there would be another page's address.
 */
export function withOgUrl(templateHtml, route) {
  if (/<meta property=["']og:url["']/.test(templateHtml)) {
    throw new Error('prerender: the index.html template already has an og:url; it must not.');
  }
  if (!templateHtml.includes('</head>')) throw new Error('prerender: the index.html template has no </head>.');
  return templateHtml.replace('</head>', () => `<meta property="og:url" content="${SITE_ORIGIN + route}" />\n</head>`);
}

/** The <meta name> withLandingPath writes and src/lib/signupAttribution.ts reads. A copy of
 *  LANDING_PATH_META there (a build script cannot import TypeScript from src); seoFiles.test.tsx
 *  fails if the two differ. */
export const LANDING_PATH_META = 'fynora-landing-path';

/**
 * Adds <meta name="fynora-landing-path" content="<route>"> before </head>: the page's own route,
 * which the web app reads on arrival to record which public page a sign-up began on
 * (src/lib/signupAttribution.ts). Written from the route this script is rendering, never from
 * anything a visitor controls, so the value can only be a real public page's address.
 *
 * Only the prerendered public pages get it, the homepage included. The blank SPA shell (/auth,
 * /app/...), the not-found page and the template itself must not: they are not landing pages, and a
 * mistyped address that is answered with the 404 page must never be recorded as one. Throws if the
 * template already has one, like withCanonical, since every document is built from one template.
 */
export function withLandingPath(templateHtml, route) {
  if (new RegExp(`<meta name=["']${LANDING_PATH_META}["']`).test(templateHtml)) {
    throw new Error('prerender: the index.html template already has a landing-path meta; it must not.');
  }
  if (!templateHtml.includes('</head>')) throw new Error('prerender: the index.html template has no </head>.');
  if (!/^\/([a-z0-9_~-][a-z0-9._~-]*(\/[a-z0-9_~-][a-z0-9._~-]*)*)?$/.test(route)) {
    throw new Error(`prerender: ${JSON.stringify(route)} is not a plain lower-case page path, so it cannot be a landing path.`);
  }
  return templateHtml.replace('</head>', () => `<meta name="${LANDING_PATH_META}" content="${route}" />\n</head>`);
}

/**
 * Adds <script defer src="<src>"> as the first thing in the page, before the module bundle: the early
 * copy of the sign-up capture (src/signupCapture.ts). The main bundle is large and deferred, so a
 * prerendered page can be on screen, links and all, long before main.tsx runs; this ~1 KB classic
 * script is fetched at the top of the head and, being deferred and earlier in the document, runs
 * before the bundle does and without waiting for it. Without it a visitor who clicks a link in that
 * window starts a second page load whose capture would take that page as the landing page and have no
 * referrer. It is NOT inline: the site's CSP has no 'unsafe-inline' for scripts.
 *
 * Only the documents that carry a landing-path marker get it (the homepage and the other prerendered
 * pages): the blank shell and the 404 page have no content to click in that window and no marker to
 * read. Throws if the template already has one, like withCanonical.
 */
export function withEarlyCapture(templateHtml, src) {
  if (/data-fynora-early-capture/.test(templateHtml)) {
    throw new Error('prerender: the index.html template already has the early-capture script; it must not.');
  }
  if (!/^\/assets\/signup-capture-[0-9a-f]{8,}\.js$/.test(src)) {
    throw new Error(`prerender: ${JSON.stringify(src)} is not the built early-capture script.`);
  }
  if (!templateHtml.includes('<title>')) throw new Error('prerender: the index.html template has no <title> to put the early-capture script before.');
  // Before <title>: ahead of everything that can delay it, and ahead of the module bundle Vite adds
  // later in the head (deferred scripts, module scripts included, run in document order).
  return templateHtml.replace('<title>', () => `<script defer src="${src}" data-fynora-early-capture></script>\n  <title>`);
}

/**
 * Adds the page's JSON-LD <script> tags (src/lib/structuredData.ts, via ssr-entry) before </head>.
 * Throws if the template already carries some: the prerender runs once per route from one shared
 * template, so a block already there would be another page's.
 */
export function withStructuredData(templateHtml, scriptsHtml) {
  if (/application\/ld\+json/.test(templateHtml)) {
    throw new Error('prerender: the index.html template already has JSON-LD; it must not.');
  }
  if (!templateHtml.includes('</head>')) {
    throw new Error('prerender: the index.html template has no </head> to add structured data to.');
  }
  if (typeof scriptsHtml !== 'string' || scriptsHtml.trim() === '') {
    throw new Error('prerender: withStructuredData was given no script tags.');
  }
  return templateHtml.replace('</head>', () => `${scriptsHtml}\n</head>`);
}

// The face the homepage hero's headline is set in: `.m-display` is Manrope 800 (src/index.css), and
// every character of the headline is in the latin subset (@fontsource's unicode-range split, see
// src/fonts.ts).
const HERO_FONT_FILE = /^manrope-latin-800-normal-[\w-]+\.woff2$/;

/**
 * Picks the hero headline's font file out of dist/assets. Throws unless exactly one file matches,
 * so a renamed or doubled asset fails the build instead of shipping a preload for nothing.
 */
export function heroFontAsset(assetFileNames) {
  const matches = assetFileNames.filter((name) => HERO_FONT_FILE.test(name));
  if (matches.length !== 1) {
    throw new Error(`prerender: expected one Manrope 800 latin woff2 in dist/assets, found ${matches.length}: ${matches.join(', ')}`);
  }
  return matches[0];
}

/**
 * Preloads the homepage hero's headline font, right after <title> so it is requested alongside the
 * stylesheet rather than after it. Without it the browser only finds the font once the stylesheet
 * has been parsed and the headline laid out, so the first paint sets the prerendered headline in
 * the fallback face. React's headline, painted later in Manrope, is then larger and becomes the
 * page's Largest Contentful Paint, which is the delay the prerendered first frame removes (see
 * src/pages/HomeCrawlerFallback.tsx). `crossorigin` is required: fonts are always fetched in CORS
 * mode, and a preload without it is not reused.
 */
export function withFontPreload(templateHtml, href) {
  if (!templateHtml.includes('</title>')) {
    throw new Error('prerender: the index.html template has no </title> to add a font preload after.');
  }
  if (/rel=["']preload["'][^>]*as=["']font["']/.test(templateHtml)) {
    throw new Error('prerender: the index.html template already preloads a font; it must not.');
  }
  return templateHtml.replace(
    '</title>',
    () => `</title>\n  <link rel="preload" href="${href}" as="font" type="font/woff2" crossorigin />`
  );
}

/**
 * Adds `<meta name="robots" content="noindex">` before </head>, for the prerendered not-found page
 * (dist/404.html). A crawler that does not run JavaScript never sees the tag useRobotsNoindex adds
 * on the client, so the static file has to carry it. Throws if the template already has a robots
 * meta: the prerender runs from one shared template, and a tag already there belongs to the build
 * policy (scripts/crawlPolicy.mjs runs after this, not before).
 */
export function withRobotsNoindex(templateHtml) {
  if (/<meta name="robots"/.test(templateHtml)) {
    throw new Error('prerender: the index.html template already has a robots meta; it must not.');
  }
  if (!templateHtml.includes('</head>')) {
    throw new Error('prerender: the index.html template has no </head> to add a robots meta to.');
  }
  return templateHtml.replace('</head>', () => '<meta name="robots" content="noindex" />\n</head>');
}

// The three kinds of document prerender.mjs writes from the one shared template, each with the
// address tags it should have and no others. (The fourth document, the blank shell, is
// spaShellHtml in spaShell.mjs.) They are here, not inline in prerender.mjs, so seoFiles.test.tsx
// can hold each to its rule without running a build.

/**
 * The homepage, dist/index.html: the template's own title and description, the hero font preload,
 * and the canonical and og:url for "/".
 *
 * The address tags go on this OUTPUT, never into frontend/index.html. For as long as the build had
 * no 404.html, Cloudflare Pages answered every path without a file with index.html, so a canonical
 * in it named the homepage as the address of /cookie-policy, /trust and /your-data, which were not
 * prerendered then. With a top-level 404.html Pages serves index.html at "/" only (measured on a
 * Pages preview and on production, 2026-10-10: /index and /index.html answer 308 to "/", an unknown
 * path gets 404.html with a 404), so the file can say where it lives. The template still cannot:
 * withCanonical and withOgUrl refuse a template that has the tag, and the local servers (`vite dev`,
 * `wrangler dev`) still answer every unknown path with index.html.
 */
export function templateForHomepage(templateHtml, heroFontHref, earlyCaptureSrc) {
  const page = withLandingPath(withOgUrl(withCanonical(withFontPreload(templateHtml, heroFontHref), '/'), '/'), '/');
  return earlyCaptureSrc ? withEarlyCapture(page, earlyCaptureSrc) : page;
}

/** Every other prerendered page: its own title, description, social tags, canonical and og:url, and
 *  its landing-path marker. */
export function templateForPage(templateHtml, { title, description, route, earlyCaptureSrc }) {
  const page = withLandingPath(
    withPageMeta(withCanonical(withTitle(templateHtml, title), route), { title, description, route }), route);
  return earlyCaptureSrc ? withEarlyCapture(page, earlyCaptureSrc) : page;
}

/**
 * The not-found page, dist/404.html: its own title and description, a robots noindex, and no
 * canonical and no og:url (route: null). A noindex page names no address of its own.
 */
export function templateForNotFound(templateHtml, { title, description }) {
  return withRobotsNoindex(withPageMeta(withTitle(templateHtml, title), { title, description, route: null }));
}
