import { useEffect } from 'react';
import { canonicalUrl, isNonProductionBuild } from '../lib/siteUrl';

/**
 * Points <link rel="canonical"> at this page's preferred URL while the page is mounted, and puts
 * the head back the way it found it on unmount.
 *
 * Client-side on purpose for the homepage, and in addition to the prerendered tag for the other
 * public pages. index.html carries NO canonical: it is the template every other built document
 * starts from, and it was once what production answered with for any route the prerender did not
 * list, where a canonical baked into it told search engines that /cookie-policy, /trust and
 * /your-data were duplicates of the homepage. (Production now serves it only at "/"; the local
 * servers still answer every unknown path with it.)
 *
 * Does nothing on a non-production build (dev-app, PR previews). Those are served with noindex, and
 * a page that says both "do not index me" and "the real one is over there" gives search engines a
 * conflicting signal. For the same reason it does nothing when given `null`: that is how a page
 * that carries noindex itself (NotFound, through PublicLayout's `noindex` prop) opts out.
 */
export function useCanonical(path: string | null): void {
  useEffect(() => {
    if (path === null || isNonProductionBuild()) return;
    const href = canonicalUrl(path);
    const existing = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
    if (existing) {
      const previous = existing.getAttribute('href');
      existing.setAttribute('href', href);
      return () => {
        if (previous === null) existing.removeAttribute('href');
        else existing.setAttribute('href', previous);
      };
    }
    const created = document.createElement('link');
    created.rel = 'canonical';
    created.href = href;
    document.head.appendChild(created);
    return () => {
      created.remove();
    };
  }, [path]);
}
