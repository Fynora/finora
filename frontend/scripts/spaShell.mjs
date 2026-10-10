// The blank document served to routes that only exist in the browser (/auth, /reset-password,
// /app/... -- see public/_redirects, which names them and explains the mechanism).
//
// Why it exists: dist/index.html is the prerendered HOMEPAGE, and it is also what Cloudflare Pages
// answers with for any path that has no file of its own. So a direct visit to /auth or an emailed
// /reset-password link painted the homepage -- headline, "Get started" buttons and all -- until the
// main bundle ran and React replaced it: about 1.6 s on a throttled phone, cold. The shell is the
// same built document with nothing inside #root, so those routes paint the page background and
// then their own content, never someone else's.
import { noindexHtml } from './crawlPolicy.mjs';

/** The built file. Served at SPA_SHELL_PATH: Pages drops the extension (and 308s the .html URL). */
export const SPA_SHELL_FILE = 'spa-shell.html';
export const SPA_SHELL_PATH = '/spa-shell';

const ROOT_DIV = '<div id="root"></div>';

/**
 * The shell, from Vite's built index.html BEFORE any page is prerendered into it.
 *
 * It is marked noindex and loses any canonical. Every route it is served for is already disallowed
 * in robots.txt (spaShell.test.ts holds that line), so this changes nothing for them. It is for
 * SPA_SHELL_PATH itself, which answers 200 like any other file: without it that URL is a blank
 * duplicate of the homepage's head that a crawler would be free to index.
 */
export function spaShellHtml(template) {
  if (!template.includes(ROOT_DIV)) {
    throw new Error(`spaShell: the template has no empty ${ROOT_DIV} -- it must be taken before prerendering.`);
  }
  return noindexHtml(template);
}

/**
 * Parses a _redirects file into its rules. Same reading as Cloudflare's own parser
 * (workers-shared parseRedirects, read in wrangler 4.146.0): `#` starts a comment (a whole line, or the
 * rest of one after whitespace), blank lines are skipped, and a rule is `<from> <to> [status]` with 302 as the default status.
 */
export function parseRedirects(text) {
  return text
    .split('\n')
    .map((line, i) => ({ line: line.trim(), lineNumber: i + 1 }))
    .filter(({ line }) => line !== '' && !line.startsWith('#'))
    .map(({ line, lineNumber }) => {
      const [from, to, status = '302'] = line.replace(/\s+#.*$/, '').split(/\s+/);
      return { from, to, status: Number(status), lineNumber };
    });
}

/** True for a rule Cloudflare evaluates as dynamic (a splat or a placeholder), not by exact path. */
export function isDynamicRule(rule) {
  return rule.from.includes('*') || /\/:[A-Za-z]/.test(rule.from);
}

/**
 * The rule that answers `pathname`, or undefined. An exact static rule wins over any dynamic one
 * (Cloudflare checks the static table first); otherwise the first matching dynamic rule, top down.
 * A splat matches greedily, including the empty string, so `/app/*` matches `/app/` but not `/app`.
 */
export function matchRedirect(rules, pathname) {
  const exact = rules.find((rule) => !isDynamicRule(rule) && rule.from === pathname);
  if (exact) return exact;
  return rules.filter(isDynamicRule).find((rule) => {
    const pattern = rule.from
      .split(/(\*|:[A-Za-z]\w*)/)
      .map((part) => {
        if (part === '*') return '.*';
        if (part.startsWith(':')) return '[^/]+';
        return part.replace(/[.+?^${}()|[\]\\]/g, '\\$&');
      })
      .join('');
    return new RegExp(`^${pattern}$`).test(pathname);
  });
}
