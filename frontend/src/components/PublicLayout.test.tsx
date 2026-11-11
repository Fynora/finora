import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PublicLayout, PublicSection } from './PublicLayout';
import Terms from '../pages/Terms';
import RefundPolicy from '../pages/RefundPolicy';
import axe from 'axe-core';
import { waitForAxeIdle } from '../test/a11y.measure';

const APP_TITLE = 'Bank statement analyzer for Indian banks and cards — Fynora';

describe('PublicLayout document title', () => {
  beforeEach(() => {
    document.title = APP_TITLE;
  });
  afterEach(() => {
    document.title = APP_TITLE;
  });

  it('titles the tab after the page, not the one title every page used to share', () => {
    render(
      <MemoryRouter>
        <PublicLayout title="Terms & Conditions">body</PublicLayout>
      </MemoryRouter>
    );
    expect(document.title).toBe('Terms & Conditions — Fynora');
  });

  it('does not repeat the brand when the title already names Fynora', () => {
    render(
      <MemoryRouter>
        <PublicLayout title="About Fynora">body</PublicLayout>
      </MemoryRouter>
    );
    expect(document.title).toBe('About Fynora');
  });

  it('restores the previous title when the page unmounts', () => {
    const { unmount } = render(
      <MemoryRouter>
        <PublicLayout title="Terms & Conditions">body</PublicLayout>
      </MemoryRouter>
    );
    unmount();
    expect(document.title).toBe(APP_TITLE);
  });

  it('follows a title change without stacking suffixes or losing the original', () => {
    const { rerender, unmount } = render(
      <MemoryRouter>
        <PublicLayout title="One">body</PublicLayout>
      </MemoryRouter>
    );
    rerender(
      <MemoryRouter>
        <PublicLayout title="Two">body</PublicLayout>
      </MemoryRouter>
    );
    expect(document.title).toBe('Two — Fynora');
    unmount();
    expect(document.title).toBe(APP_TITLE);
  });

  it('gives distinct titles to the real pages built on it', () => {
    const titleOf = (ui: React.ReactElement) => {
      const { unmount } = render(<MemoryRouter>{ui}</MemoryRouter>);
      const t = document.title;
      unmount();
      return t;
    };
    const terms = titleOf(<Terms />);
    const refunds = titleOf(<RefundPolicy />);
    expect(terms).not.toBe(APP_TITLE);
    expect(refunds).not.toBe(APP_TITLE);
    expect(terms).not.toBe(refunds);
  });
});

// A link to one section (the notification settings' "How Fynora uses this") must land on it, not on
// the top of the page: the browser looked for the id before the sections were drawn.
describe('PublicLayout section links', () => {
  const scrollIntoView = vi.fn();
  beforeEach(() => {
    scrollIntoView.mockClear();
    Element.prototype.scrollIntoView = scrollIntoView;
  });
  afterEach(() => {
    delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
  });

  const page = (entry: string) => (
    <MemoryRouter initialEntries={[entry]}>
      <PublicLayout title="Privacy Policy">
        <PublicSection id="first" title="First">one</PublicSection>
        <PublicSection id="offers-and-news" title="Offers and News">two</PublicSection>
      </PublicLayout>
    </MemoryRouter>
  );

  it('scrolls to the section the link names', () => {
    render(page('/privacy#offers-and-news'));

    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(document.getElementById('offers-and-news'));
  });

  it('stays put with no section named, or one that does not exist', () => {
    render(page('/privacy'));
    render(page('/privacy#no-such-section'));
    render(page('/privacy#%E0%A4%A'));

    expect(scrollIntoView).not.toHaveBeenCalled();
  });
});

describe('PublicLayout landmarks', () => {
  // Every public page is built on this layout, so content it puts outside a landmark is missing from
  // landmark navigation on all of them at once. The title band (the page's <h1> and its subtitle)
  // once sat between <header> and <main>, and axe reported `region` on every prerendered page.
  const page = () => (
    <MemoryRouter initialEntries={['/privacy']}>
      <PublicLayout title="Privacy Policy" subtitle="Last updated: November 2026. How Fynora handles your data." description="What Fynora collects and why.">
        <PublicSection title="First">
          <p>Body text.</p>
        </PublicSection>
      </PublicLayout>
    </MemoryRouter>
  );

  it('puts every piece of the page inside a landmark', async () => {
    const { container } = render(page());

    const root = container.firstElementChild!;
    expect([...root.children].map((el) => el.tagName.toLowerCase())).toEqual(['header', 'main', 'footer']);

    // The same check axe runs on the built site, as a second measure of the structure above.
    await waitForAxeIdle();
    const results = await axe.run(document.body, { runOnly: ['region'], resultTypes: ['violations'] });
    expect(results.violations.flatMap((v) => v.nodes.map((n) => n.target.join(' ')))).toEqual([]);
  });

  it('keeps the <h1>, its subtitle and the description attribute inside <main>', () => {
    render(page());

    const main = screen.getByRole('main');
    expect(main).toContainElement(screen.getByRole('heading', { level: 1, name: 'Privacy Policy' }));
    expect(main.querySelector('[data-seo="description"]')?.textContent).toMatch(/^Last updated/);
    expect(main.querySelector('[data-seo-description]')?.getAttribute('data-seo-description')).toBe('What Fynora collects and why.');
  });
});
