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
import { isNonProductionBuild, pageDescription } from '../src/lib/siteUrl';
import { useCanonical } from '../src/hooks/useCanonical';
import { pageDescriptionFromMarkup, withPageMeta } from './prerenderTitle.mjs';

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
    expect(indexHtml).not.toMatch(/fonts\.googleapis\.com|fonts\.gstatic\.com/);
    const fonts = fs.readFileSync(path.join(root, 'src/fonts.ts'), 'utf-8');
    for (const face of ['inter/latin-400', 'inter/latin-800', 'manrope/latin-600', 'manrope/latin-800', 'caveat/latin-600']) {
      expect(fonts).toContain(`@fontsource/${face}.css`);
    }
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

  it('has no og:url: this file is the fallback for every unlisted route', () => {
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

    const tag = document.createElement('meta');
    tag.setAttribute('name', 'description');
    tag.setAttribute('content', 'placeholder');
    document.head.appendChild(tag);
    try {
      const { unmount } = rtlRender(<MemoryRouter><Page /></MemoryRouter>);
      expect(tag.getAttribute('content')).toBe('The real description, with "quotes" & an ampersand.');
      unmount();
      expect(tag.getAttribute('content')).toBe('placeholder');
    } finally {
      tag.remove();
    }
  });
});

describe('PublicLayout meta description at runtime', () => {
  afterEach(() => {
    document.head.querySelectorAll('meta[name="description"]').forEach((m) => m.remove());
  });

  it('sets the page\'s description and puts the previous one back on unmount', () => {
    const tag = document.createElement('meta');
    tag.setAttribute('name', 'description');
    tag.setAttribute('content', 'the homepage description');
    document.head.appendChild(tag);

    const { unmount } = rtlRender(<MemoryRouter><Terms /></MemoryRouter>);
    expect(tag.getAttribute('content')).toBe(
      'The terms for using Fynora: who you contract with, account and acceptable-use rules, how automated features and billing work, and the limits of liability.'
    );
    unmount();
    expect(tag.getAttribute('content')).toBe('the homepage description');
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
