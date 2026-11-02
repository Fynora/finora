import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PublicLayout, PublicSection } from './PublicLayout';
import Terms from '../pages/Terms';
import RefundPolicy from '../pages/RefundPolicy';

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
