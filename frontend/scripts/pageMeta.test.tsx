import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, StaticRouter } from 'react-router-dom';
import { render as rtlRender } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import About from '../src/pages/About';
import Careers from '../src/pages/Careers';
import Contact from '../src/pages/Contact';
import CookiePolicy from '../src/pages/CookiePolicy';
import DataPromise from '../src/pages/DataPromise';
import Help from '../src/pages/Help';
import Privacy from '../src/pages/Privacy';
import RefundPolicy from '../src/pages/RefundPolicy';
import ShippingPolicy from '../src/pages/ShippingPolicy';
import Terms from '../src/pages/Terms';
import TrustSecurity from '../src/pages/TrustSecurity';
import { PublicLayout } from '../src/components/PublicLayout';
import { hero } from '../src/pages/landing/landing-config';
import { HOME_TITLE, SITE_DESCRIPTION, isNonProductionBuild, pageDescription } from '../src/lib/siteUrl';
import { useCanonical } from '../src/hooks/useCanonical';
import { pageDescriptionFromMarkup, withOgUrl, withPageMeta } from './prerenderTitle.mjs';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const indexHtml = fs.readFileSync(path.join(root, 'index.html'), 'utf-8');
// The comments in index.html explain why a tag is absent by naming it, so "is there an og:url" must
// be asked of the real <meta> tags, not of the raw file text.
const indexMetaTags = (indexHtml.match(/<meta\b[^>]*>/g) ?? []).join('\n');

function markup(Component: React.ComponentType): string {
  return renderToStaticMarkup(
    <StaticRouter location="/x">
      <Component />
    </StaticRouter>
  );
}

const decode = (html: string) => {
  const el = document.createElement('textarea');
  el.innerHTML = html;
  return el.value;
};

describe('index.html description and social tags', () => {
  const meta = (attr: string, key: string) =>
    new RegExp(`<meta ${attr}="${key}" content="([^"]*)"`).exec(indexHtml)?.[1];

  it('describes the product with the homepage hero\'s own reviewed sentence', () => {
    expect(meta('name', 'description')).toBe(hero.blurb);
    expect(meta('property', 'og:description')).toBe(hero.blurb);
  });

  it("is what the browser falls back to: the app's copies of the title and description match this file", () => {
    // usePageDescription and Landing put these back once the document's own page is gone. A copy
    // that drifted would have the homepage describe itself two ways, by file and by script.
    expect(HOME_TITLE).toBe(/<title>([^<]*)<\/title>/.exec(indexHtml)?.[1]);
    expect(HOME_TITLE).toBe(meta('property', 'og:title'));
    expect(SITE_DESCRIPTION).toBe(meta('name', 'description'));
    expect(SITE_DESCRIPTION).toBe(meta('property', 'og:description'));
    expect(SITE_DESCRIPTION).toBe(hero.blurb);
  });

  it('no longer says Fynora helps you "grow" your money, which it does not do', () => {
    expect(indexMetaTags).not.toMatch(/grow your money/i);
  });

  it('titles the homepage with what the product is, brand last, and keeps og:title the same string', () => {
    const title = /<title>([^<]*)<\/title>/.exec(indexHtml)?.[1];
    expect(title).toBe('Bank statement analyzer for Indian banks and cards — Fynora');
    expect(title!.length).toBeLessThanOrEqual(60);
    expect(title).toMatch(/^Bank statement/);
    expect(meta('property', 'og:title')).toBe(title);
  });

  it('loads no stylesheet or font from Google: the fonts are self-hosted (src/fonts.ts)', () => {
    // Plain substring checks, not one alternation regex: CodeQL reads an unanchored host regex as a
    // URL check that arbitrary hosts could satisfy and fails the PR on it, even in a test.
    expect(indexHtml).not.toContain('fonts.googleapis.com');
    expect(indexHtml).not.toContain('fonts.gstatic.com');
    const fonts = fs.readFileSync(path.join(root, 'src/fonts.ts'), 'utf-8');
    for (const face of ['inter/400', 'inter/800', 'manrope/600', 'manrope/800', 'caveat/600']) {
      expect(fonts).toContain(`@fontsource/${face}.css`);
    }
    // The per-subset files (latin-400.css) have no unicode-range and no rupee glyph; the rupee sign
    // U+20B9 lives in latin-ext. Only the per-weight files keep every subset with its range.
    expect(fonts).not.toMatch(/@fontsource\/[a-z]+\/latin/);
    expect(fs.readFileSync(path.join(root, 'src/main.tsx'), 'utf-8')).toContain("import './fonts';");
  });

  it('has the tags a link preview needs, with the large-image card', () => {
    expect(meta('property', 'og:site_name')).toBe('Fynora');
    expect(meta('property', 'og:type')).toBe('website');
    expect(meta('property', 'og:title')).toBe('Bank statement analyzer for Indian banks and cards — Fynora');
    expect(meta('property', 'og:image')).toBe('https://app.fynora.net/og-image.png');
    expect(meta('property', 'og:image:type')).toBe('image/png');
    expect(meta('property', 'og:image:width')).toBe('1200');
    expect(meta('property', 'og:image:height')).toBe('630');
    expect(meta('property', 'og:image:alt')?.length).toBeGreaterThan(20);
    expect(meta('name', 'twitter:card')).toBe('summary_large_image');
  });

  // The tags above only help if the file they point at is real, is the size they declare, and is
  // light enough for chat apps (WhatsApp in particular drops previews for heavy images).
  describe('the social image file', () => {
    const file = path.join(root, 'public/og-image.png');

    it('exists in public/, at the path the og:image URL names', () => {
      const url = new URL(meta('property', 'og:image')!);
      expect(url.pathname).toBe('/og-image.png');
      expect(fs.existsSync(file)).toBe(true);
    });

    it('is a real PNG of exactly the 1200x630 the tags declare', () => {
      const bytes = fs.readFileSync(file);
      expect(bytes.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a'); // PNG signature
      // The IHDR chunk starts at byte 12; width and height are the next two big-endian uint32s.
      const width = bytes.readUInt32BE(16);
      const height = bytes.readUInt32BE(20);
      expect(`${width}x${height}`).toBe(`${meta('property', 'og:image:width')}x${meta('property', 'og:image:height')}`);
    });

    it('is under 300 KB', () => {
      expect(fs.statSync(file).size).toBeLessThan(300 * 1024);
    });
  });

  it('has no og:url in the source file: it is the template for every other document the build writes', () => {
    // The homepage's og:url is added to dist/index.html by the build (seoFiles.test.tsx holds it).
    // Here it would also land in the blank shell and the not-found page, and stop the build.
    expect(indexMetaTags).not.toMatch(/og:url/);
    expect(indexMetaTags.length).toBeGreaterThan(0);
  });
});

describe('page description', () => {
  it('drops a leading "Last updated" and keeps the rest of the page\'s own subtitle', () => {
    expect(pageDescription('Last updated: September 2026. Applies to any paid Fynora subscription (Plus).'))
      .toBe('Applies to any paid Fynora subscription (Plus).');
    expect(pageDescription('A financial operating system built for people who are tired of spreadsheets.'))
      .toBe('A financial operating system built for people who are tired of spreadsheets.');
    expect(pageDescription('Last updated: August 2026.')).toBeNull();
    expect(pageDescription(undefined)).toBeNull();
  });

  // Every page scripts/ssr-entry.tsx prerenders, so a page added there is held to the same bar.
  const PRERENDERED_PAGES = [About, Careers, Contact, CookiePolicy, DataPromise, Help, Privacy, RefundPolicy, ShippingPolicy, Terms, TrustSecurity];

  it('is the same string in the browser and in the prerendered HTML, for every prerendered page', () => {
    // index.html is not loaded in tests, so provide the tag PublicLayout updates, as the real page has.
    const tag = document.createElement('meta');
    tag.setAttribute('name', 'description');
    tag.setAttribute('content', 'placeholder');
    document.head.appendChild(tag);
    try {
      for (const Page of PRERENDERED_PAGES) {
        const prerendered = decode(pageDescriptionFromMarkup(markup(Page))!);
        const { unmount } = rtlRender(<MemoryRouter><Page /></MemoryRouter>);
        expect(tag.getAttribute('content'), Page.name).toBe(prerendered);
        unmount();
        expect(prerendered, Page.name).not.toMatch(/^Last updated/);
      }
    } finally {
      tag.remove();
    }
  });

  it('is long enough for a search result to use, and short enough not to be cut, on every page', () => {
    // Google wrote its own snippets for /help, /terms, /privacy and /about when the descriptions
    // were the pages' subtitles (41 to 76 characters). 70 is the floor a sentence needs to say what
    // the page answers; 160 is where results truncate.
    for (const Page of PRERENDERED_PAGES) {
      const description = decode(pageDescriptionFromMarkup(markup(Page))!);
      expect(description.length, `${Page.name}: ${description}`).toBeGreaterThanOrEqual(70);
      expect(description.length, `${Page.name}: ${description}`).toBeLessThanOrEqual(160);
      expect(description, Page.name).not.toMatch(/ -- /);
    }
  });

  it('is the explicit description when a page gives one, otherwise the subtitle, in both places', () => {
    function Page() {
      return (
        <PublicLayout title="T" subtitle="A short subtitle." description='The real description, with "quotes" & an ampersand.'>
          body
        </PublicLayout>
      );
    }
    expect(decode(pageDescriptionFromMarkup(markup(Page))!)).toBe('The real description, with "quotes" & an ampersand.');
    function Plain() {
      return <PublicLayout title="T" subtitle="A short subtitle.">body</PublicLayout>;
    }
    expect(pageDescriptionFromMarkup(markup(Plain))).toBe('A short subtitle.');
    expect(markup(Plain)).not.toContain('data-seo-description');
    // An empty or blank description is no description: both sides fall back to the subtitle.
    function Blank() {
      return <PublicLayout title="T" subtitle="A short subtitle." description="  ">body</PublicLayout>;
    }
    expect(pageDescriptionFromMarkup(markup(Blank))).toBe('A short subtitle.');
    const blankTag = document.createElement('meta');
    blankTag.setAttribute('name', 'description');
    blankTag.setAttribute('content', 'placeholder');
    document.head.appendChild(blankTag);
    try {
      const { unmount } = rtlRender(<MemoryRouter><Blank /></MemoryRouter>);
      expect(blankTag.getAttribute('content')).toBe('A short subtitle.');
      unmount();
    } finally {
      blankTag.remove();
    }

    const tag = document.createElement('meta');
    tag.setAttribute('name', 'description');
    tag.setAttribute('content', 'placeholder');
    document.head.appendChild(tag);
    try {
      const { unmount } = rtlRender(<MemoryRouter><Page /></MemoryRouter>);
      expect(tag.getAttribute('content')).toBe('The real description, with "quotes" & an ampersand.');
      unmount();
      // The site's description, not "placeholder": see the runtime block below.
      expect(tag.getAttribute('content')).toBe(SITE_DESCRIPTION);
    } finally {
      tag.remove();
    }
  });
});

describe('PublicLayout description and social text at runtime', () => {
  const HEAD_TAGS: [string, string, string][] = [
    ['name', 'description', 'the first document description'],
    ['property', 'og:title', 'The first document — Fynora'],
    ['property', 'og:description', 'the first document description'],
  ];
  const content = (attr: string, key: string) =>
    document.head.querySelector(`meta[${attr}="${key}"]`)?.getAttribute('content');

  // The head a visitor is in after opening some OTHER page first: every tag is that page's.
  const arriveOnAnotherDocument = () => {
    for (const [attr, key, value] of HEAD_TAGS) {
      const tag = document.createElement('meta');
      tag.setAttribute(attr, key);
      tag.setAttribute('content', value);
      document.head.appendChild(tag);
    }
  };

  afterEach(() => {
    for (const [attr, key] of HEAD_TAGS) document.head.querySelectorAll(`meta[${attr}="${key}"]`).forEach((m) => m.remove());
  });

  it("sets the page's description, og:title and og:description, the strings its prerendered file has", () => {
    arriveOnAnotherDocument();
    rtlRender(<MemoryRouter><Terms /></MemoryRouter>);
    const description =
      'The terms for using Fynora: who you contract with, account and acceptable-use rules, how automated features and billing work, and the limits of liability.';
    expect(content('name', 'description')).toBe(description);
    expect(content('property', 'og:description')).toBe(description);
    expect(content('property', 'og:title')).toBe('Terms & Conditions — Fynora');
    // The same three strings scripts/prerender.mjs writes into terms.html.
    expect(decode(pageDescriptionFromMarkup(markup(Terms))!)).toBe(description);
    expect(document.title).toBe(content('property', 'og:title'));
  });

  it("puts the SITE's text back on unmount, not the first document's", () => {
    // Measured in Chrome on production, 2026-10-10: open /terms, go to the homepage inside the
    // app, and its description, og:title and og:description were still the terms page's. Every page
    // restored "what it found", and what it found was the document it happened to be shown in.
    arriveOnAnotherDocument();
    const { unmount } = rtlRender(<MemoryRouter><Terms /></MemoryRouter>);
    unmount();
    expect(content('name', 'description')).toBe(SITE_DESCRIPTION);
    expect(content('property', 'og:description')).toBe(SITE_DESCRIPTION);
    expect(content('property', 'og:title')).toBe(HOME_TITLE);
  });

  it('adds no tag the document does not have', () => {
    const { unmount } = rtlRender(<MemoryRouter><Terms /></MemoryRouter>);
    expect(document.head.querySelector('meta[name="description"]')).toBeNull();
    expect(document.head.querySelector('meta[property="og:title"]')).toBeNull();
    unmount();
    expect(document.head.querySelector('meta[name="description"]')).toBeNull();
  });
});

describe('withPageMeta', () => {
  const TEMPLATE = indexHtml;

  it('points the description and og tags at the page and adds og:url', () => {
    const out = withPageMeta(TEMPLATE, {
      title: 'Terms &amp; Conditions — Fynora',
      description: 'Please read these terms carefully before using Fynora.',
      route: '/terms',
    });
    expect(out).toContain('<meta name="description" content="Please read these terms carefully before using Fynora." />');
    expect(out).toContain('<meta property="og:title" content="Terms &amp; Conditions — Fynora" />');
    expect(out).toContain('<meta property="og:description" content="Please read these terms carefully before using Fynora." />');
    expect(out).toContain('<meta property="og:url" content="https://app.fynora.net/terms" />');
    expect(out).not.toContain(hero.blurb);
  });

  it('adds no og:url for the not-found page (route: null), which has no address of its own', () => {
    const out = withPageMeta(TEMPLATE, { title: 'Page not found — Fynora', description: 'Missing.', route: null });
    expect(out).toContain('<meta property="og:title" content="Page not found — Fynora" />');
    expect((out.match(/<meta\b[^>]*>/g) ?? []).join('\n')).not.toMatch(/og:url/);
  });

  it('adds og:url once: a template that already has one, or has no </head>, is refused', () => {
    const once = withOgUrl(TEMPLATE, '/');
    expect(once).toContain('<meta property="og:url" content="https://app.fynora.net/" />\n</head>');
    expect(() => withOgUrl(once, '/')).toThrow(/already has an og:url/);
    expect(() => withPageMeta(once, { title: 'T', description: 'D', route: '/terms' })).toThrow(/already has an og:url/);
    expect(() => withOgUrl('<html></html>', '/')).toThrow(/no <\/head>/);
  });

  it('does not interpret $ sequences in a description as replacement patterns', () => {
    const out = withPageMeta(TEMPLATE, { title: 'T', description: 'Save $& and $1', route: '/x' });
    expect(out).toContain('content="Save $& and $1"');
  });

  it('throws if the template lost a tag it replaces, rather than shipping the homepage text', () => {
    expect(() => withPageMeta('<html><head></head></html>', { title: 'T', description: 'D', route: '/x' }))
      .toThrow(/no <meta name="description">/);
  });
});

describe('non-production builds', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('are recognised by the dev API host and by nothing else', () => {
    expect(isNonProductionBuild('https://dev-api.fynora.net')).toBe(true);
    expect(isNonProductionBuild('https://api.fynora.net')).toBe(false);
    expect(isNonProductionBuild(undefined)).toBe(false);
    expect(isNonProductionBuild('')).toBe(false);
  });

  it('get no canonical at runtime: noindex plus a canonical is a conflicting signal', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://dev-api.fynora.net');
    function Probe() {
      useCanonical('/terms');
      return null;
    }
    const { unmount } = rtlRender(<Probe />);
    expect(document.head.querySelectorAll('link[rel="canonical"]')).toHaveLength(0);
    unmount();

    vi.stubEnv('VITE_API_BASE_URL', 'https://api.fynora.net');
    const second = rtlRender(<Probe />);
    expect(document.head.querySelectorAll('link[rel="canonical"]')).toHaveLength(1);
    second.unmount();
  });
});
