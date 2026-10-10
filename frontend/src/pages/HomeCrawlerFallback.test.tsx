// @vitest-environment node
//
// Node, not jsdom: scripts/prerender.mjs renders this page with react-dom/server in plain Node,
// where there is no window or document. A component that touches either during render fails the
// production build, so this test renders it the same way.
import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { HomeCrawlerFallback } from './HomeCrawlerFallback';
import { askFyn, capabilities, faq, hero, heroBadges, heroIntelligence, importSection, problem } from './landing/landing-config';

function renderPage(): string {
  return renderToStaticMarkup(
    <StaticRouter location="/">
      <HomeCrawlerFallback />
    </StaticRouter>
  );
}

// The markup with tags removed and entities decoded, so copy can be matched as a reader sees it.
function visibleText(html: string): string {
  return html
    .replace(/<[^>]+>/g, ' ')
    .replace(/&#x27;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ');
}

describe('HomeCrawlerFallback', () => {
  it('renders in plain Node, as the prerender does', () => {
    expect(() => renderPage()).not.toThrow();
  });

  it('opens with the real hero first frame, with a single <h1> that is the hero headline', () => {
    const html = renderPage();
    expect(html).toContain('data-hero-first-frame=""');
    expect(html.match(/<h1[\s>]/g)).toHaveLength(1);
    expect(html).toMatch(/<h1 class="m-display mb-5"[^>]*>Upload a bank statement\.<br\/>/);
    // Nothing hidden behind an entrance animation: a crawler or a no-JS visitor reads all of it.
    expect(html).not.toMatch(/opacity:\s*0[;"]/);
    expect(html).not.toMatch(/visibility:\s*hidden/);
    expect(html).not.toContain('<canvas');
    // The hero's dashboard and score row are not in this page, only the space they will take. Their
    // demo figures are illustration, and here they would be read as statements about the product.
    expect(html.match(/data-hero-pending=""/g)).toHaveLength(2);
    expect(html).not.toMatch(/data-hero-pending=""[^>]*>[^<]/);
    expect(html).not.toContain(heroIntelligence.heading);
    for (const badge of heroBadges) expect(html).not.toContain(badge.label);
  });

  it('keeps every piece of landing copy the crawler page carried', () => {
    const text = visibleText(renderPage());
    const expected = [
      hero.headline,
      hero.headlineAccent,
      hero.blurb,
      problem.title,
      `${problem.closer} ${problem.closerMuted}`,
      `${importSection.title} ${importSection.titleLine2}`,
      importSection.blurb,
      ...importSection.proofs.flatMap((p) => [p.title, p.body]),
      `${capabilities.title} ${capabilities.titleLine2}`,
      capabilities.blurb,
      ...capabilities.items.flatMap((i) => [i.title, i.body]),
      askFyn.title,
      askFyn.blurb,
      faq.title,
      ...faq.items.flat(),
    ];
    for (const sentence of expected) expect(text).toContain(sentence.replace(/\s+/g, ' '));
  });

  it('keeps the policy links Google OAuth-branding verification looks for', () => {
    const html = renderPage();
    for (const href of ['/terms', '/privacy', '/cookie-policy', '/trust', '/your-data', '/refund-policy', '/shipping-policy', '/contact', '/about', '/help']) {
      expect(html).toContain(`href="${href}"`);
    }
  });

  it('gives its sections the ids the Nav and the hero button link to', () => {
    // "See how it works" (#how) is on screen in this frame, so a tap before the real page mounts
    // has to land somewhere. Every fragment linked from this page except the two sections it does
    // not carry must resolve to an element in it.
    const html = renderPage();
    const linked = new Set([...html.matchAll(/href="#([^"]+)"/g)].map((m) => m[1]));
    expect([...linked].sort()).toEqual(['ask-fyn', 'faq', 'features', 'how', 'pricing', 'trust']);
    for (const id of linked) {
      if (id === 'pricing' || id === 'trust') continue;
      expect(html).toContain(`<section id="${id}"`);
    }
  });
});
