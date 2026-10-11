import { HOME_TITLE, NOT_FOUND_DOCUMENT_TITLE, SITE_DESCRIPTION } from './siteUrl';

/**
 * Takes the not-found page's description and social text out of the head when the document is
 * dist/404.html, before any page renders. Called once from main.tsx.
 *
 * 404.html is the one built file that can be on screen under a route that is not its own. Pages
 * matches paths case-sensitively and React Router does not, so /Auth, /App/transactions or /About
 * are answered with 404.html (and HTTP 404) and then show the real page. A public page writes its
 * own description and og: text over the file's (usePageDescription). The sign-in pages and the app
 * set a title and nothing else, so they kept "Page not found" as og:title and the not-found
 * sentence as their description for as long as the visitor stayed off the public pages.
 *
 * Putting the site's defaults in first makes every page start from the same head the blank shell
 * gives it. When the address really is unknown, the not-found page mounts a moment later and
 * writes its own text back, exactly as it does after a dead link inside the app.
 *
 * The title is left alone: every route sets its own, and changing it here would flash the
 * homepage's in the tab of a visitor who is about to be told the page is missing.
 *
 * The file is recognised by its og:title, which only 404.html has (scripts/prerender.mjs writes it
 * from the page's own heading; prerenderTitle.test.tsx holds that string equal to the constant).
 */
export function dropNotFoundDocumentText(doc: Document = document): void {
  const set = (selector: string, content: string) =>
    doc.head.querySelector<HTMLMetaElement>(selector)?.setAttribute('content', content);
  if (doc.head.querySelector('meta[property="og:title"]')?.getAttribute('content') !== NOT_FOUND_DOCUMENT_TITLE) return;
  set('meta[name="description"]', SITE_DESCRIPTION);
  set('meta[property="og:title"]', HOME_TITLE);
  set('meta[property="og:description"]', SITE_DESCRIPTION);
}
