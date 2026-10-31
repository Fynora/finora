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
import { HelpArticleView } from '../src/pages/HelpArticle';
import { HELP_ARTICLES, HELP_ARTICLE_ROUTE, helpArticlePath } from '../src/pages/helpArticles';
import StatementCodeIndex from '../src/pages/StatementCodeIndex';
import { StatementCodeView } from '../src/pages/StatementCodePage';
import { STATEMENT_CODES, STATEMENT_CODE_ROUTE, statementCodePath } from '../src/pages/statementCodes';
import CookiePolicy from '../src/pages/CookiePolicy';
import TrustSecurity from '../src/pages/TrustSecurity';
import DataPromise from '../src/pages/DataPromise';
import { HomeCrawlerFallback } from '../src/pages/HomeCrawlerFallback';
import NotFound from '../src/pages/NotFound';

// Re-exported so scripts/prerender.mjs (plain Node, no TypeScript) can reach them through this
// bundle: the JSON-LD each route's HTML carries, built from the pages' own data.
export { jsonLdScripts, structuredDataFor } from '../src/lib/structuredData';
// llms.txt and llms-full.txt, built from the site's own copy (see src/lib/llmsTxt.ts).
export { llmsFullTxt, llmsTxt } from '../src/lib/llmsTxt';

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
  // In the sitemap, so a crawler that does not run JavaScript must get the real page. A route
  // this file does not list has no file: it once got the homepage from index.html, and would now
  // get the not-found page with a 404.
  '/cookie-policy': page('/cookie-policy', CookiePolicy),
  '/trust': page('/trust', TrustSecurity),
  '/your-data': page('/your-data', DataPromise),
  '/bank-statement-codes': page('/bank-statement-codes', StatementCodeIndex),
};

/**
 * A set of pages that share one pattern route in App.tsx, `/<section>/:slug`, one page per record
 * in a data module (the Help answers today). Each page is prerendered to
 * dist/<section>/<slug>.html, which Cloudflare Pages serves at the extensionless /<section>/<slug>.
 * They need no line in public/_redirects (a rewrite there would replace the file with the blank
 * noindex shell), and a slug with no record has no file, so it gets 404.html with a 404.
 *
 * To add a collection (bank pages at /banks/<slug>, for example):
 *   1. A data module with one record per page, each with a stable lower-case slug (see
 *      src/pages/helpArticles.ts: HELP_ARTICLES, HELP_ARTICLE_ROUTE, helpArticlePath).
 *   2. A view component that renders one record in PublicLayout, with a plain-text <h1> (it becomes
 *      the <title>) and a `description` of 70 to 160 characters (it becomes the meta description),
 *      and a route component that looks the slug up and renders NotFound when there is none
 *      (src/pages/HelpArticle.tsx).
 *   3. `<Route path="/<section>/:slug" ...>` in src/App.tsx.
 *   4. An entry in `pageCollections` below. scripts/prerender.mjs writes every page of every
 *      collection; it needs no edit.
 *   5. One <url> per page in public/sitemap.xml, in the collection's order. seoFiles.test.tsx and
 *      spaShell.test.ts read `pageCollections` and fail until the sitemap matches and no
 *      _redirects rule covers the section.
 *   6. Structured data for the pages in src/lib/structuredData.ts (structuredDataFor). Without a
 *      branch of its own a page gets only Home > <title> breadcrumbs.
 *   7. The view in pageMeta.test.tsx's PRERENDERED_PAGES, which holds every prerendered page's
 *      description to 70 to 160 characters and to the same string in the browser and the HTML.
 * A hub page for the collection (/banks) is an ordinary fixed page: `routes` above, OUTPUT_FILES in
 * prerender.mjs, and the sitemap.
 */
export interface PageCollection {
  /** The pattern route in App.tsx, `/<section>/:slug`. */
  route: string;
  /** Every page's address and its renderer, in sitemap order. */
  pages: Record<string, () => string>;
}

/** One page per Help answer, built from HELP_ARTICLES, so an article added there needs no edit here. */
export const helpArticlePages: Record<string, () => string> = Object.fromEntries(
  HELP_ARTICLES.map((article) => {
    const route = helpArticlePath(article);
    return [route, page(route, () => <HelpArticleView article={article} />)];
  }),
);

/** One page per bank statement code, built from STATEMENT_CODES. */
export const statementCodePages: Record<string, () => string> = Object.fromEntries(
  STATEMENT_CODES.map((code) => {
    const route = statementCodePath(code);
    return [route, page(route, () => <StatementCodeView code={code} />)];
  }),
);

export const pageCollections: PageCollection[] = [
  { route: HELP_ARTICLE_ROUTE, pages: helpArticlePages },
  { route: STATEMENT_CODE_ROUTE, pages: statementCodePages },
];

/**
 * The not-found page, written to dist/404.html. Deliberately NOT an entry in `routes`: those are
 * the indexable pages, and seoFiles.test.tsx holds every one of them to the sitemap. This one must
 * never be in the sitemap, carries noindex, and gets no canonical or JSON-LD.
 */
export const notFoundPage: () => string = page('/404', NotFound);
