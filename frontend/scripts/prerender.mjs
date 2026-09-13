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
};

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

  const { routes } = await import(pathToFileURL(path.join(ssrOutDir, 'ssr-entry.mjs')));

  const template = fs.readFileSync(path.join(distDir, 'index.html'), 'utf-8');
  if (!template.includes(ROOT_DIV)) {
    throw new Error(`dist/index.html doesn't contain ${ROOT_DIV} -- template shape changed, update this script.`);
  }

  for (const [route, fileName] of Object.entries(OUTPUT_FILES)) {
    const renderRoute = routes[route];
    const appHtml = renderRoute();
    const outHtml = template.replace(ROOT_DIV, `<div id="root">${appHtml}</div>`);
    fs.writeFileSync(path.join(distDir, fileName), outHtml);
    console.log(`prerender: ${route} -> dist/${fileName} (${appHtml.length} chars of markup)`);
  }

  fs.rmSync(ssrOutDir, { recursive: true, force: true });
}

main().catch((err) => {
  console.error('prerender failed:', err);
  process.exit(1);
});
