import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, StaticRouter } from 'react-router-dom';
import { render as rtlRender } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import About from '../src/pages/About';
import Careers from '../src/pages/Careers';
import Terms from '../src/pages/Terms';
import RefundPolicy from '../src/pages/RefundPolicy';
import Help from '../src/pages/Help';
import { pageTitleFromMarkup, withTitle } from './prerenderTitle.mjs';

const TEMPLATE = '<html><head><title>Fynora — Personal finance, simplified</title></head><body><div id="root"></div></body></html>';

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

  it('gives different pages different titles, and none the shared one', () => {
    const titles = [Terms, RefundPolicy, Help].map((c) => pageTitleFromMarkup(render(c)));
    expect(new Set(titles).size).toBe(3);
    expect(titles).not.toContain('Fynora — Personal finance, simplified');
  });

  it('replaces only the <title> in the template', () => {
    const out = withTitle(TEMPLATE, 'Terms &amp; Conditions — Fynora');
    expect(out).toContain('<title>Terms &amp; Conditions — Fynora</title>');
    expect(out).not.toContain('Personal finance, simplified');
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
});
