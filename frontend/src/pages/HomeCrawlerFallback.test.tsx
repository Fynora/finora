// @vitest-environment node
//
// Node, not jsdom: scripts/prerender.mjs renders this page with react-dom/server in plain Node,
// where there is no window or document. A component that touches either during render fails the
// production build, so this test renders it the same way.
import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { HomeCrawlerFallback } from './HomeCrawlerFallback';
import { askFyn, capabilities, faq, hero, importSection, problem } from './landing/landing-config';

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
    expect(html).not.toContain('<canvas');
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
});
