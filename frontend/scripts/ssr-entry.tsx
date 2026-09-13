import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom';
import Privacy from '../src/pages/Privacy';
import Terms from '../src/pages/Terms';
import { HomeCrawlerFallback } from '../src/pages/HomeCrawlerFallback';

/**
 * Bundled by scripts/prerender.mjs via Vite's SSR build, then imported from plain Node -- this is
 * the only file that needs to know how to render each route; the script just calls what's here.
 * Each entry is wrapped in a StaticRouter because Privacy/Terms/PublicLayout use react-router-dom's
 * <Link>, which throws without a router context.
 */
export const routes: Record<string, () => string> = {
  '/': () => renderToStaticMarkup(
    <StaticRouter location="/"><HomeCrawlerFallback /></StaticRouter>
  ),
  '/privacy': () => renderToStaticMarkup(
    <StaticRouter location="/privacy"><Privacy /></StaticRouter>
  ),
  '/terms': () => renderToStaticMarkup(
    <StaticRouter location="/terms"><Terms /></StaticRouter>
  ),
};
