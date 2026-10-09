import { useEffect } from 'react';

/**
 * Keeps `<meta name="robots" content="noindex">` in the head while the page is mounted, and puts
 * the head back the way it found it on unmount.
 *
 * For the not-found page. Cloudflare serves the prerendered index.html with HTTP 200 for every
 * route this app does not list (wrangler.jsonc, `not_found_handling: "single-page-application"`),
 * so a crawler that runs the bundle sees a real page at /any-typo. This tag is what tells it not to
 * index that page; the status code cannot (scripts/prerender.mjs has the measured behaviour).
 *
 * A tag already in the head is reused, never duplicated. One that already says noindex (the
 * "noindex, nofollow" scripts/crawlPolicy.mjs bakes into every non-production HTML file) is left
 * exactly as it is, so this never weakens it. Any other is set to noindex while this page is mounted
 * and restored afterwards, the same way useCanonical treats an existing canonical link.
 */
export function useRobotsNoindex(enabled = true): void {
  useEffect(() => {
    if (!enabled) return;
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
