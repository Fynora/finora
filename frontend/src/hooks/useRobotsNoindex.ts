import { useEffect } from 'react';
import { isNonProductionBuild } from '../lib/siteUrl';

/**
 * Says whether the page that is mounted may be indexed, in `<meta name="robots">`.
 *
 * `enabled` (the not-found page): keeps `noindex` in the head while the page is mounted. In
 * production an unknown address is answered with the build's 404.html and HTTP 404
 * (scripts/prerender.mjs), and that file carries the tag itself. The hook is for the ways the page
 * is reached with no such status: a client-side navigation to a dead link, and the local servers
 * (`vite dev`, `wrangler dev`), which answer every unknown path with a 200. A tag already in the
 * head is reused, never duplicated. One that already says noindex is left exactly as it is, so this
 * never weakens it. Any other is set to noindex while this page is mounted and restored afterwards.
 *
 * Not `enabled` (a page that should be indexed: every other PublicLayout page, and the homepage):
 * takes a leftover `noindex` OUT of the head. Two production files carry one, 404.html and the
 * blank shell for /auth and /app, and it outlives both. Measured in Chrome on production,
 * 2026-10-10: open /auth, follow the link to the homepage or to /terms inside the app, and the head
 * still says "noindex, nofollow", on /terms right beside the canonical. And /About, which Pages
 * answers with 404.html before React mounts the real page, kept that file's "noindex". No crawler
 * that fetches those addresses directly sees either (it gets the page's own file), so this is
 * about the live page saying one thing, not two.
 *
 * Never on a non-production build (dev-app, PR previews). There the tag is the build's own policy,
 * "noindex, nofollow" in every file (scripts/crawlPolicy.mjs), and it must stay on every page. The
 * `X-Robots-Tag` header those builds are served with says the same and is out of this hook's reach.
 *
 * (useCanonical does not restore either: a canonical names one page, so it is removed when that
 * page unmounts.)
 */
export function useRobotsNoindex(enabled = true): void {
  useEffect(() => {
    if (!enabled) {
      if (isNonProductionBuild()) return;
      const leftover = document.head.querySelector<HTMLMetaElement>('meta[name="robots"]');
      if (leftover && /\bnoindex\b/i.test(leftover.getAttribute('content') ?? '')) leftover.remove();
      return;
    }
    const existing = document.head.querySelector<HTMLMetaElement>('meta[name="robots"]');
    if (existing) {
      const previous = existing.getAttribute('content');
      if (previous !== null && /\bnoindex\b/i.test(previous)) return;
      existing.setAttribute('content', 'noindex');
      return () => {
        if (previous === null) existing.removeAttribute('content');
        else existing.setAttribute('content', previous);
      };
    }
    const created = document.createElement('meta');
    created.name = 'robots';
    created.content = 'noindex';
    document.head.appendChild(created);
    return () => {
      created.remove();
    };
  }, [enabled]);
}
