import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, StaticRouter } from 'react-router-dom';
import { render as rtlRender } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import About from '../src/pages/About';
import Privacy from '../src/pages/Privacy';
import RefundPolicy from '../src/pages/RefundPolicy';
import Terms from '../src/pages/Terms';
import TrustSecurity from '../src/pages/TrustSecurity';
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

  it('has the tags a link preview needs, with a card type that fits a square logo', () => {
    expect(meta('property', 'og:site_name')).toBe('Fynora');
    expect(meta('property', 'og:type')).toBe('website');
    expect(meta('property', 'og:title')).toBe('Fynora — Personal finance, simplified');
    expect(meta('property', 'og:image')).toBe('https://app.fynora.net/favicon.png');
    expect(meta('name', 'twitter:card')).toBe('summary');
    expect(fs.existsSync(path.join(root, 'public/favicon.png'))).toBe(true);
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

  it('is the same string in the browser and in the prerendered HTML, for real pages', () => {
    // index.html is not loaded in tests, so provide the tag PublicLayout updates, as the real page has.
    const tag = document.createElement('meta');
    tag.setAttribute('name', 'description');
    tag.setAttribute('content', 'placeholder');
    document.head.appendChild(tag);
    try {
      for (const Page of [About, Privacy, RefundPolicy, Terms, TrustSecurity]) {
        const prerendered = decode(pageDescriptionFromMarkup(markup(Page))!);
        const { unmount } = rtlRender(<MemoryRouter><Page /></MemoryRouter>);
        expect(tag.getAttribute('content'), Page.name).toBe(prerendered);
        unmount();
        expect(prerendered.length, Page.name).toBeGreaterThan(10);
        expect(prerendered, Page.name).not.toMatch(/^Last updated/);
      }
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
      'Please read these terms carefully before using Fynora.'
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
