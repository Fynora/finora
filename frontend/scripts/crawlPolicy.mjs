// Runs after prerender.mjs (see package.json's "build" script).
//
// Keeps search engines from indexing anything that is not production. One Cloudflare Pages project
// serves three kinds of build (docs/operations/deployment/deployment-guide.md, "Dev environment"):
//
//   - the `main` branch            -> app.fynora.net       (production; the only one to index)
//   - the persistent `dev` branch  -> dev-app.fynora.net   (branch-alias custom domain)
//   - every PR preview             -> <hash>.<project>.pages.dev
//
// Until this existed, the dev and preview builds were as crawlable as production, so an indexed
// copy of the app pointing at the dev API was possible.
//
// What a non-production build gets:
//   - `X-Robots-Tag: noindex, nofollow` on every response (public/_headers)
//   - `<meta name="robots" content="noindex, nofollow">` in every built HTML file, in case a proxy
//     strips the header
//   - no canonical link: noindex plus a canonical pointing at production is the conflicting signal
//     Google warns about (src/hooks/useCanonical.ts skips it at runtime for the same reason)
//   - a robots.txt with no Sitemap line
//
// What it deliberately does NOT do: `Disallow: /` in robots.txt. A crawler forbidden to fetch a URL
// never sees that URL's noindex, so a page already indexed would stay indexed.
//
// A build it cannot identify is treated as production. Failing the other way, a production deploy
// with a missing variable would silently remove the whole site from search results, which is far
// worse than a dev URL being indexable for one more deploy.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const PRODUCTION_BRANCH = 'main';

/**
 * True for a build that is not production. Two independent signals that describe the same fact, so
 * either is enough: Cloudflare Pages sets CF_PAGES=1 and CF_PAGES_BRANCH on its builds, and every
 * non-production build inherits VITE_API_BASE_URL=https://dev-api.fynora.net from the Preview bucket.
 */
export function isNonProduction(env) {
  const apiBase = env.VITE_API_BASE_URL ?? '';
  // A build pointed at the PRODUCTION API is production, whatever branch Cloudflare says it is. The
  // branch name "main" is an assumption about how the Pages project is configured; this makes sure
  // that if the assumption is ever wrong, the failure is a dev URL left indexable, never production
  // wrongly hidden from search.
  if (/^https?:\/\/api\.fynora\.net(\/|$)/.test(apiBase)) return false;
  const onPagesBranchThatIsNotProduction =
    env.CF_PAGES === '1' && Boolean(env.CF_PAGES_BRANCH) && env.CF_PAGES_BRANCH !== PRODUCTION_BRANCH;
  const usesDevApi = /\/\/dev-api\./.test(apiBase);
  return onPagesBranchThatIsNotProduction || usesDevApi;
}

const NOINDEX = '<meta name="robots" content="noindex, nofollow" />';

/** Adds the robots meta before </head> (once) and removes any canonical link. */
export function noindexHtml(html) {
  if (!html.includes('</head>')) throw new Error('crawlPolicy: an HTML file has no </head>.');
  let out = html.replace(/<link rel="canonical"[^>]*>\s*/g, '');
  if (!/<meta name="robots"/.test(out)) {
    out = out.replace('</head>', () => `${NOINDEX}\n</head>`);
  }
  return out;
}

/**
 * Adds `X-Robots-Tag: noindex, nofollow` to the existing `/*` block, as its first header.
 *
 * It must be INSIDE the existing block, never a second `/*` block appended after it. Tried and
 * measured on a real Cloudflare preview: with two `/*` blocks, the FIRST block's headers stopped
 * being sent at all (no Content-Security-Policy, no Strict-Transport-Security) while the second's
 * were. Cloudflare's docs read as if matching blocks merge; on the deployed preview they did not.
 * An unmodified preview of another branch served the CSP, which is how the difference was proven.
 */
export function noindexHeaders(headersText) {
  const lines = headersText.split('\n');
  // Only a real header line counts. A comment that merely mentions X-Robots-Tag must not make this
  // think the rule is already there and silently skip adding it.
  if (lines.some((line) => /^\s+X-Robots-Tag\s*:/i.test(line))) return headersText;

  const header = '  X-Robots-Tag: noindex, nofollow';
  const at = lines.findIndex((line) => line.trim() === '/*');
  if (at === -1) {
    const sep = headersText === '' || headersText.endsWith('\n') ? '' : '\n';
    return `${headersText}${sep}/*\n${header}\n`;
  }
  return [...lines.slice(0, at + 1), header, ...lines.slice(at + 1)].join('\n');
}

/** robots.txt for a non-production build: same rules, no pointer at the production sitemap. */
export function robotsForNonProduction(robotsText) {
  const rules = robotsText.split('\n').filter((line) => !/^Sitemap:/i.test(line)).join('\n').trimEnd();
  return `# Non-production build. Crawlers are told not to index it by the X-Robots-Tag header and the\n# robots meta tag, NOT by "Disallow: /": a page a crawler may not fetch is a page whose noindex it\n# never sees.\n${rules}\n`;
}

function htmlFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return htmlFiles(full);
    return entry.name.endsWith('.html') ? [full] : [];
  });
}

/** Applies the policy to a built dist directory. Returns how many HTML files it changed. */
export function applyToDist(distDir) {
  let changed = 0;
  for (const file of htmlFiles(distDir)) {
    const before = fs.readFileSync(file, 'utf-8');
    const after = noindexHtml(before);
    if (after !== before) {
      fs.writeFileSync(file, after);
      changed += 1;
    }
  }
  const headersPath = path.join(distDir, '_headers');
  fs.writeFileSync(headersPath, noindexHeaders(readIfPresent(headersPath) ?? ''));
  const robotsPath = path.join(distDir, 'robots.txt');
  const robots = readIfPresent(robotsPath);
  if (robots !== null) fs.writeFileSync(robotsPath, robotsForNonProduction(robots));
  return changed;
}

/** The file's text, or null if it does not exist. Reads and handles ENOENT, rather than checking
 *  existence first, so the file cannot change between the check and the read. */
function readIfPresent(file) {
  try {
    return fs.readFileSync(file, 'utf-8');
  } catch (err) {
    if (err && err.code === 'ENOENT') return null;
    throw err;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const distDir = path.join(path.dirname(path.dirname(fileURLToPath(import.meta.url))), 'dist');
  if (!fs.existsSync(path.join(distDir, 'index.html'))) {
    throw new Error('crawlPolicy: dist/index.html not found -- run `vite build` and prerender first.');
  }
  if (isNonProduction(process.env)) {
    const changed = applyToDist(distDir);
    console.log(`crawlPolicy: non-production build -> noindex on ${changed} HTML files, _headers and robots.txt updated`);
  } else {
    console.log('crawlPolicy: production build (or one that cannot be identified) -> left indexable');
  }
}
