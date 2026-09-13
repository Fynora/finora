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
import { HomeCrawlerFallback } from '../src/pages/HomeCrawlerFallback';

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
};
