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
import { SPA_SHELL_FILE, spaShellHtml } from './spaShell.mjs';

const frontendRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const ssrOutDir = path.join(frontendRoot, '.prerender-ssr');
const distDir = path.join(frontendRoot, 'dist');

const ROOT_DIV = '<div id="root"></div>';

// Where each route's rendered markup gets written. "/" overwrites the SPA's own index.html;
// every other entry is a new file, which Cloudflare Pages serves for the extensionless path
// (/privacy from privacy.html; /privacy/ and /privacy.html are redirected to it). A route NOT
// listed here has no file: it is served the blank shell if public/_redirects names it, and the
// not-found page below, with HTTP 404, if it does not. spaShell.test.ts reads this list.
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
// The file name is the mechanism. Production is a Cloudflare PAGES project, and a top-level
// 404.html changes what Pages does with a path that has no file and no rewrite: without one it
// answers with index.html and HTTP 200 (the homepage, at every dead URL: a soft 404); with one it
// answers with this file and HTTP 404. Measured on this change's Pages preview, 2026-10-10:
//
//   - an unknown path, and a case variant of a real page (/Privacy): this file, 404.
//   - /auth, /reset-password, /app/... : still 200, because public/_redirects rewrites them to the
//     blank shell and a rewrite is applied before the lookup that would have ended here.
//   - /404 itself: this file, 200, like any other page with a file. The noindex meta covers it.
//   - a missing file under /assets/: 404, plain text (functions/assets/[[path]].ts).
//
// So this file is also what a route gets if it is added to App.tsx and forgotten in _redirects.
// Before the rewrites existed that was every browser-only route: this exact file, added on its own,
// returned 404 for /auth and /app/transactions on a preview. spaShell.test.ts is the guard.
//
// `wrangler dev` (`npm run preview`) does not show any of this. It runs Workers static assets from
// wrangler.jsonc, where `not_found_handling: "single-page-application"` answers every unmatched
// path with index.html and 200 and the name 404.html means nothing. Use
// `npx wrangler pages dev dist`, and a PR's Pages preview for the final word.
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

  // The blank shell for browser-only routes, taken from the template before the loop below fills
  // index.html with the homepage. public/_redirects is what points /auth, /app/... at it.
  fs.writeFileSync(path.join(distDir, SPA_SHELL_FILE), spaShellHtml(template));
  console.log(`prerender: blank shell -> dist/${SPA_SHELL_FILE}`);

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
