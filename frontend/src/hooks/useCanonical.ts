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
 * Does nothing on a non-production build (dev-app, PR previews). Those are served with noindex, and
 * a page that says both "do not index me" and "the real one is over there" gives search engines a
 * conflicting signal. For the same reason it does nothing when given `null`: that is how a page
 * that carries noindex itself (NotFound, through PublicLayout's `noindex` prop) opts out.
 */
export function useCanonical(path: string | null): void {
  useEffect(() => {
    if (path === null || isNonProductionBuild()) return;
    const existing = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
    const link = existing ?? document.createElement('link');
    link.rel = 'canonical';
    link.href = canonicalUrl(path);
    if (!existing) document.head.appendChild(link);
    return () => {
      link.remove();
    };
  }, [path]);
}
