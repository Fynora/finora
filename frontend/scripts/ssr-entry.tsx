import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom';
import type { ComponentType } from 'react';
import Privacy from '../src/pages/Privacy';
import Terms from '../src/pages/Terms';
import About from '../src/pages/About';
import Careers from '../src/pages/Careers';
import Contact from '../src/pages/Contact';
import RefundPolicy from '../src/pages/RefundPolicy';
import ShippingPolicy from '../src/pages/ShippingPolicy';
import Help from '../src/pages/Help';
import CookiePolicy from '../src/pages/CookiePolicy';
import TrustSecurity from '../src/pages/TrustSecurity';
import DataPromise from '../src/pages/DataPromise';
import { HomeCrawlerFallback } from '../src/pages/HomeCrawlerFallback';
import NotFound from '../src/pages/NotFound';

// Re-exported so scripts/prerender.mjs (plain Node, no TypeScript) can reach them through this
// bundle: the JSON-LD each route's HTML carries, built from the pages' own data.
export { jsonLdScripts, structuredDataFor } from '../src/lib/structuredData';

/**
 * Bundled by scripts/prerender.mjs via Vite's SSR build, then imported from plain Node -- this is
 * the only file that needs to know how to render each route; the script just calls what's here.
 * Wrapped in a StaticRouter because these pages (via PublicLayout, or Contact/RefundPolicy/
 * ShippingPolicy directly) use react-router-dom's <Link>, which throws without a router context.
 */
function page(path: string, Component: ComponentType): () => string {
  return () => renderToStaticMarkup(
    <StaticRouter location={path}><Component /></StaticRouter>
  );
}

export const routes: Record<string, () => string> = {
  '/': page('/', HomeCrawlerFallback),
  '/privacy': page('/privacy', Privacy),
  '/terms': page('/terms', Terms),
  '/about': page('/about', About),
  '/careers': page('/careers', Careers),
  '/contact': page('/contact', Contact),
  '/refund-policy': page('/refund-policy', RefundPolicy),
  '/shipping-policy': page('/shipping-policy', ShippingPolicy),
  '/help': page('/help', Help),
  // In the sitemap, so a crawler that does not run JavaScript must get the real page, not the
  // homepage that index.html serves for every route this file does not list.
  '/cookie-policy': page('/cookie-policy', CookiePolicy),
  '/trust': page('/trust', TrustSecurity),
  '/your-data': page('/your-data', DataPromise),
};

/**
 * The not-found page, written to dist/404.html. Deliberately NOT an entry in `routes`: those are
 * the indexable pages, and seoFiles.test.tsx holds every one of them to the sitemap. This one must
 * never be in the sitemap, carries noindex, and gets no canonical or JSON-LD.
 */
export const notFoundPage: () => string = page('/404', NotFound);
