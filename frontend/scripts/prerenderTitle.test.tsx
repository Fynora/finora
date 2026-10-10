import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, StaticRouter } from 'react-router-dom';
import { render as rtlRender } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import About from '../src/pages/About';
import Careers from '../src/pages/Careers';
import Terms from '../src/pages/Terms';
import RefundPolicy from '../src/pages/RefundPolicy';
import Help from '../src/pages/Help';
import NotFound, { NOT_FOUND_DOCUMENT_TITLE } from '../src/pages/NotFound';
import {
  decodeEntities,
  heroFontAsset,
  pageDescriptionFromMarkup,
  pageHeadingFromMarkup,
  pageTitleFromMarkup,
  withFontPreload,
  withStructuredData,
  withTitle,
} from './prerenderTitle.mjs';

const TEMPLATE = '<html><head><title>Bank statement analyzer for Indian banks and cards — Fynora</title></head><body><div id="root"></div></body></html>';

function render(Component: React.ComponentType): string {
  return renderToStaticMarkup(
    <StaticRouter location="/x">
      <Component />
    </StaticRouter>
  );
}

describe('prerender page titles', () => {
  it('takes the title from the page\'s own <h1>, HTML-escaped as React already made it', () => {
    // "Terms & Conditions" is escaped to "&amp;" in the markup, and that is what belongs in <title>.
    expect(pageTitleFromMarkup(render(Terms))).toBe('Terms &amp; Conditions — Fynora');
    expect(pageTitleFromMarkup(render(RefundPolicy))).toBe('Refund &amp; Cancellation Policy — Fynora');
    expect(pageTitleFromMarkup(render(Help))).toBe('Help Center — Fynora');
  });

  it('does not repeat the brand when the page title already names it', () => {
    expect(pageTitleFromMarkup(render(About))).toBe('About Fynora');
    expect(pageTitleFromMarkup(render(Careers))).toBe('Careers at Fynora');
  });

  // The two builders (PublicLayout's client-side document.title, and this script's <title>) are
  // separate code that must produce the same string. This is what keeps them from drifting.
  it('gives the same title in the browser and in the prerendered HTML, for every prerendered page', () => {
    const decode = (html: string) => {
      const el = document.createElement('textarea');
      el.innerHTML = html;
      return el.value;
    };
    for (const Page of [Terms, RefundPolicy, Help, About, Careers]) {
      const prerendered = decode(pageTitleFromMarkup(render(Page))!);
      const { unmount } = rtlRender(<MemoryRouter><Page /></MemoryRouter>);
      expect(document.title).toBe(prerendered);
      unmount();
    }
  });

  it('gives dist/404.html the title and description the browser shows, and no canonical', () => {
    // The not-found page is prerendered outside the route table (ssr-entry's notFoundPage).
    const prerendered = pageTitleFromMarkup(render(NotFound));
    expect(prerendered).toBe(NOT_FOUND_DOCUMENT_TITLE);
    expect(pageDescriptionFromMarkup(render(NotFound))).not.toBeNull();
    expect(render(NotFound)).not.toContain('canonical');
    const { unmount } = rtlRender(<MemoryRouter><NotFound /></MemoryRouter>);
    expect(document.title).toBe(prerendered);
    unmount();
  });

  it('gives different pages different titles, and none the shared one', () => {
    const titles = [Terms, RefundPolicy, Help].map((c) => pageTitleFromMarkup(render(c)));
    expect(new Set(titles).size).toBe(3);
    expect(titles).not.toContain('Bank statement analyzer for Indian banks and cards — Fynora');
  });

  it('replaces only the <title> in the template', () => {
    const out = withTitle(TEMPLATE, 'Terms &amp; Conditions — Fynora');
    expect(out).toContain('<title>Terms &amp; Conditions — Fynora</title>');
    expect(out).not.toContain('Bank statement analyzer');
    expect(out).toContain('<div id="root"></div>');
  });

  it('does not interpret $ sequences in a title as regex replacement patterns', () => {
    expect(withTitle(TEMPLATE, "Save $& more $1 — Fynora")).toContain('<title>Save $& more $1 — Fynora</title>');
  });

  it('returns null for markup with no usable <h1>, and throws for a template with no <title>', () => {
    expect(pageTitleFromMarkup('<div>no heading</div>')).toBeNull();
    expect(pageTitleFromMarkup('<h1>   </h1>')).toBeNull();
    expect(() => withTitle('<html><head></head></html>', 'X')).toThrow(/no <title>/);
  });

  it('also exposes the bare heading, for the breadcrumb name, and decodes what React escaped', () => {
    expect(pageHeadingFromMarkup(render(Terms))).toBe('Terms &amp; Conditions');
    expect(decodeEntities(pageHeadingFromMarkup(render(Terms))!)).toBe('Terms & Conditions');
    expect(decodeEntities('Ask Fyn&#x27;s &quot;limits&quot; &lt;b&gt; &amp;lt;')).toBe('Ask Fyn\'s "limits" <b> &lt;');
    expect(pageHeadingFromMarkup('<div>no heading</div>')).toBeNull();
  });
});

describe('withStructuredData', () => {
  const SCRIPT = '<script type="application/ld+json">{"@type":"WebSite"}</script>';

  it('adds the script tags just before </head>', () => {
    const out = withStructuredData(TEMPLATE, SCRIPT);
    expect(out).toContain(`${SCRIPT}\n</head>`);
    expect(out).toContain('<div id="root"></div>');
  });

  it('refuses a template that already carries JSON-LD, one with no </head>, and empty input', () => {
    expect(() => withStructuredData(withStructuredData(TEMPLATE, SCRIPT), SCRIPT)).toThrow(/already has JSON-LD/);
    expect(() => withStructuredData('<html></html>', SCRIPT)).toThrow(/no <\/head>/);
    expect(() => withStructuredData(TEMPLATE, '')).toThrow(/no script tags/);
  });

  it('does not interpret $ sequences in the JSON as replacement patterns', () => {
    const out = withStructuredData(TEMPLATE, '<script type="application/ld+json">{"t":"$& $1"}</script>');
    expect(out).toContain('{"t":"$& $1"}');
  });
});

describe('hero font preload', () => {
  it('picks the one Manrope 800 latin woff2, ignoring the woff fallback, other subsets and weights', () => {
    expect(
      heroFontAsset([
        'index-abc.js',
        'manrope-latin-800-normal-BfWYOv1c.woff2',
        'manrope-latin-800-normal-uHUdIJgA.woff',
        'manrope-latin-ext-800-normal-DdFx7KEb.woff2',
        'manrope-latin-700-normal-BZp_XxE4.woff2',
      ])
    ).toBe('manrope-latin-800-normal-BfWYOv1c.woff2');
  });

  it('fails the build when the file is missing or ambiguous', () => {
    expect(() => heroFontAsset(['manrope-latin-700-normal-a.woff2'])).toThrow(/found 0/);
    expect(() => heroFontAsset(['manrope-latin-800-normal-a.woff2', 'manrope-latin-800-normal-b.woff2'])).toThrow(/found 2/);
  });

  it('adds a CORS-mode font preload right after <title>, ahead of the stylesheet', () => {
    const out = withFontPreload(TEMPLATE, '/assets/manrope-latin-800-normal-x.woff2');
    expect(out).toContain(
      '</title>\n  <link rel="preload" href="/assets/manrope-latin-800-normal-x.woff2" as="font" type="font/woff2" crossorigin />'
    );
    expect(out).toContain('<div id="root"></div>');
  });

  it('refuses a template with no </title> or one that already preloads a font', () => {
    expect(() => withFontPreload('<html><head></head></html>', '/a.woff2')).toThrow(/no <\/title>/);
    expect(() => withFontPreload(withFontPreload(TEMPLATE, '/a.woff2'), '/a.woff2')).toThrow(/already preloads a font/);
  });
});
