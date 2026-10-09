// Runs after `vite build` (see package.json's "build" script). Google's OAuth-branding
// verification rejected app.fynora.net's home/privacy/terms pages because they're a pure
// client-rendered SPA shell (`<div id="root"></div>`, no visible text) -- its crawler doesn't run
// the JS bundle, so it saw blank pages. Every other public page (About/Careers/Contact/
// RefundPolicy/ShippingPolicy/Help) is linked from PublicLayout's footer on every one of these
// pages, including this app's own homepage, and was the exact same blank shell -- so this bakes
// real, crawlable HTML for all of them into the already-built dist/, using the actual page
// components (react-dom/server), not hand-duplicated copy that could drift from them.
import { build } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import {
  decodeEntities,
  pageDescriptionFromMarkup,
  pageHeadingFromMarkup,
  pageTitleFromMarkup,
  withCanonical,
  withPageMeta,
  withRobotsNoindex,
  withStructuredData,
  withTitle,
} from './prerenderTitle.mjs';

const frontendRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const ssrOutDir = path.join(frontendRoot, '.prerender-ssr');
const distDir = path.join(frontendRoot, 'dist');

const ROOT_DIV = '<div id="root"></div>';

// Where each route's rendered markup gets written. "/" overwrites the SPA's own index.html;
// every other entry is a new file Cloudflare's static-asset handling serves directly for an
// exact path match (see wrangler.jsonc's default html_handling: auto-trailing-slash, verified
// against @cloudflare/vite-plugin's own asset-worker source) -- any route not listed here still
// falls through to the SPA unchanged.
const OUTPUT_FILES = {
  '/': 'index.html',
  '/privacy': 'privacy.html',
  '/terms': 'terms.html',
  '/about': 'about.html',
  '/careers': 'careers.html',
  '/contact': 'contact.html',
  '/refund-policy': 'refund-policy.html',
  '/shipping-policy': 'shipping-policy.html',
  '/help': 'help.html',
  '/cookie-policy': 'cookie-policy.html',
  '/trust': 'trust.html',
  '/your-data': 'your-data.html',
};

// The not-found page. Written beside the routes above but NOT one of them: it is not in the sitemap,
// gets no canonical and no JSON-LD, and carries a robots noindex meta in the file itself.
//
// What Cloudflare does with this file, read from the asset worker wrangler ships (miniflare's
// workers/assets/assets.worker.js, bundled from workers-shared/asset-worker, the same code that
// serves production) and measured with `wrangler dev` on 2026-10-09:
//
//   - `not_found_handling: "single-page-application"` (wrangler.jsonc) answers EVERY unmatched path
//     with /index.html and HTTP 200. The file name 404.html means nothing in this mode: the worker's
//     notFound() only looks for 404.html under "404-page", where it serves it with HTTP 404.
//     The two modes are exclusive, so there is no configuration in which /app/* falls back to the
//     SPA shell with 200 AND an unknown path gets a 404 status.
//   - What this file DOES get is an exact-path match: /404 serves it, with 200, through the same
//     html_handling rule that serves /terms from terms.html.
//
// So the HTTP status for an unknown URL stays 200 under this configuration. What tells a crawler
// not to index it is the robots noindex meta: added in the browser by the NotFound route, which is
// what index.html renders for an unknown path, and baked into this file for /404. Switching to "404-page" would make THIS file the shell for every unlisted route with a
// real 404 status -- but also for /app/*, /auth and every other client-only route. That is a
// deliberate trade for the owner, not something to flip in a prerender script.
const NOT_FOUND_FILE = '404.html';

async function main() {
  if (!fs.existsSync(path.join(distDir, 'index.html'))) {
    throw new Error('dist/index.html not found -- run `vite build` before this script.');
  }

  // SSR-bundle scripts/ssr-entry.tsx so plain Node can import it. react/react-dom/react-router-dom
  // stay external (Vite's SSR-build default) and resolve from node_modules at import time below.
  await build({
    root: frontendRoot,
    plugins: [react()],
    build: {
      ssr: path.join(frontendRoot, 'scripts/ssr-entry.tsx'),
      outDir: ssrOutDir,
      emptyOutDir: true,
      rollupOptions: { output: { format: 'es', entryFileNames: 'ssr-entry.mjs' } },
    },
    logLevel: 'warn',
  });

  const { routes, notFoundPage, structuredDataFor, jsonLdScripts } = await import(pathToFileURL(path.join(ssrOutDir, 'ssr-entry.mjs')));

  const template = fs.readFileSync(path.join(distDir, 'index.html'), 'utf-8');
  if (!template.includes(ROOT_DIV)) {
    throw new Error(`dist/index.html doesn't contain ${ROOT_DIV} -- template shape changed, update this script.`);
  }

  for (const [route, fileName] of Object.entries(OUTPUT_FILES)) {
    const renderRoute = routes[route];
    const appHtml = renderRoute();
    // Every route but the homepage gets its own <title> (see prerenderTitle.mjs). The homepage
    // keeps index.html's own -- its <h1> is the hero headline, not a page name. A page with no
    // <h1> fails the build instead of quietly shipping the shared title again.
    let pageTemplate = template;
    let heading = null;
    if (route !== '/') {
      const title = pageTitleFromMarkup(appHtml);
      if (!title) throw new Error(`prerender: no <h1> to take a <title> from for ${route}`);
      heading = decodeEntities(pageHeadingFromMarkup(appHtml));
      const description = pageDescriptionFromMarkup(appHtml);
      if (!description) throw new Error(`prerender: no subtitle to take a description from for ${route}`);
      pageTemplate = withPageMeta(withCanonical(withTitle(template, title), route), { title, description, route });
    }
    // JSON-LD for the page (Organization/WebSite/SoftwareApplication/FAQPage on the homepage,
    // breadcrumbs elsewhere, the Help articles as an FAQPage on /help). Built from the pages' own
    // data in src/lib/structuredData.ts, so it cannot say something the page does not.
    pageTemplate = withStructuredData(pageTemplate, jsonLdScripts(structuredDataFor(route, heading)));
    const outHtml = pageTemplate.replace(ROOT_DIV, `<div id="root">${appHtml}</div>`);
    fs.writeFileSync(path.join(distDir, fileName), outHtml);
    console.log(`prerender: ${route} -> dist/${fileName} (${appHtml.length} chars of markup)`);
  }

  {
    const appHtml = notFoundPage();
    const title = pageTitleFromMarkup(appHtml);
    if (!title) throw new Error('prerender: no <h1> to take a <title> from for the not-found page');
    const description = pageDescriptionFromMarkup(appHtml);
    if (!description) throw new Error('prerender: no subtitle to take a description from for the not-found page');
    // No canonical, no og:url (route: null) and no JSON-LD: a noindex page names no address of its
    // own, and a breadcrumb trail for a page that does not exist would be a lie.
    const pageTemplate = withRobotsNoindex(withPageMeta(withTitle(template, title), { title, description, route: null }));
    fs.writeFileSync(path.join(distDir, NOT_FOUND_FILE), pageTemplate.replace(ROOT_DIV, `<div id="root">${appHtml}</div>`));
    console.log(`prerender: not-found page -> dist/${NOT_FOUND_FILE} (${appHtml.length} chars of markup)`);
  }

  fs.rmSync(ssrOutDir, { recursive: true, force: true });
}

main().catch((err) => {
  console.error('prerender failed:', err);
  process.exit(1);
});
