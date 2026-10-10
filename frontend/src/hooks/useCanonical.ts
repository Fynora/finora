import { useEffect } from 'react';
import { canonicalUrl, isNonProductionBuild } from '../lib/siteUrl';

/**
 * Points <link rel="canonical"> at this page's preferred URL while the page is mounted, and takes
 * the tag out of the head on unmount.
 *
 * In addition to the prerendered tag, not instead of it: every prerendered page, the homepage
 * included, carries its own canonical in its built HTML (scripts/prerender.mjs), because a crawler
 * that does not run JavaScript never sees this hook. On a direct visit the hook finds that tag and
 * sets it to the same address; it earns its keep on navigation inside the app, where the head is
 * still the first page's, and on a case variant such as /About, which production answers with the
 * not-found document (no canonical) before React mounts the real page.
 *
 * The tag is REMOVED on unmount, the prerendered one included, not put back to what it said before.
 * It names the page that is mounted. The one the document arrived with belongs to the first page
 * only, so putting it back when a later page unmounts, or leaving it when the first one does, would
 * name that first page as the address of whatever is shown next: a visitor who opened the homepage
 * and went on to /auth or into /app would be on a page whose head still said "/". The next page
 * that names an address adds its own.
 *
 * The source frontend/index.html carries NO canonical. It is the template every built document
 * starts from (the blank shell and the not-found page must name no address), and the local servers
 * answer every unknown path with it. The build adds the homepage's to dist/index.html only.
 *
 * `null` is how a page that carries noindex itself (NotFound, through PublicLayout's `noindex` prop)
 * says it names no address. A page that says both "do not index me" and "the real one is over
 * there" gives search engines a conflicting signal, so `null` does not just add nothing: it takes
 * out a tag that is already there. That happens when the not-found page is shown over a document
 * that is another page's file. Measured on production, 2026-10-10: Pages answers //terms with
 * terms.html (canonical /terms), React Router matches no route for "//terms", and the head then
 * said both noindex and canonical /terms.
 *
 * Does nothing on a non-production build (dev-app, PR previews). Those are served with noindex, for
 * the same conflicting-signal reason, and scripts/crawlPolicy.mjs has already taken the canonical
 * out of every file they serve.
 */
export function useCanonical(path: string | null): void {
  useEffect(() => {
    if (isNonProductionBuild()) return;
    const existing = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
    if (path === null) {
      existing?.remove();
      return;
    }
    const link = existing ?? document.createElement('link');
    link.rel = 'canonical';
    link.href = canonicalUrl(path);
    if (!existing) document.head.appendChild(link);
    return () => {
      link.remove();
    };
  }, [path]);
}
