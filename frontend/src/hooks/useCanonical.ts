import { useEffect } from 'react';
import { canonicalUrl, isNonProductionBuild } from '../lib/siteUrl';

/**
 * Points <link rel="canonical"> at this page's preferred URL while the page is mounted, and puts
 * the head back the way it found it on unmount.
 *
 * Client-side on purpose for the homepage, and in addition to the prerendered tag for the other
 * public pages. index.html carries NO canonical: any route the prerender does not list falls back
 * to the prerendered index.html, so a canonical baked into it would tell search engines that
 * /cookie-policy, /trust and /your-data are duplicates of the homepage.
 *
 * Does nothing on a non-production build (dev-app, PR previews). Those are served with noindex, and
 * a page that says both "do not index me" and "the real one is over there" gives search engines a
 * conflicting signal.
 */
export function useCanonical(path: string): void {
  useEffect(() => {
    if (isNonProductionBuild()) return;
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
