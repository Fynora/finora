import { Link } from 'react-router-dom';
import { PublicLayout } from '../components/PublicLayout';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

export const NOT_FOUND_TITLE = 'Page not found';
// `${title} — Fynora`, the rule PublicLayout and scripts/prerenderTitle.mjs apply to a title that does
// not already name the brand.
export const NOT_FOUND_DOCUMENT_TITLE = `${NOT_FOUND_TITLE} — Fynora`;
export const NOT_FOUND_SUBTITLE = 'There is no page at this address. It may have moved, or the link may have a typo in it.';

/**
 * What an unknown URL shows. Routed by App.tsx's `*` route, which used to be a redirect to the
 * homepage: a visitor who mistyped a link was silently shown the marketing page at "/" with no word
 * that anything was wrong, and a crawler following a dead link was handed the homepage with HTTP
 * 200 at the dead URL, which the 2026-10-09 SEO audit flagged as a soft 404.
 *
 * The status code is not this component's to set. It comes from the build: scripts/prerender.mjs
 * writes this page to dist/404.html, and Cloudflare Pages answers a path that has no file and no
 * rewrite with that file and HTTP 404. What the component adds is the head: PublicLayout's
 * `noindex` keeps a robots noindex meta while mounted, and no canonical, for the cases where it is
 * reached without that status (a client-side navigation, `vite dev`, `wrangler dev`).
 *
 * The title is set here as well as by PublicLayout, with the same string PublicLayout and the
 * prerender build from NOT_FOUND_TITLE (NotFound.test.tsx checks all three agree), so the tab reads
 * "Page not found — Fynora" however the two effects are ordered.
 */
export default function NotFound() {
  useDocumentTitle(NOT_FOUND_DOCUMENT_TITLE);
  return (
    <PublicLayout title={NOT_FOUND_TITLE} subtitle={NOT_FOUND_SUBTITLE} noindex>
      <p className="text-sm text-muted leading-relaxed mb-6">
        Check the address for typos, or try one of these:
      </p>
      <ul className="space-y-3 text-sm">
        <li>
          <Link to="/" className="text-primary hover:underline font-medium">Go to the homepage</Link>
        </li>
        <li>
          <Link to="/help" className="text-primary hover:underline font-medium">Browse the Help Center</Link>
        </li>
        <li>
          <Link to="/contact" className="text-primary hover:underline font-medium">Contact us</Link>
        </li>
      </ul>
    </PublicLayout>
  );
}
