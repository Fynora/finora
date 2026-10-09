import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import NotFound, { NOT_FOUND_DOCUMENT_TITLE, NOT_FOUND_TITLE } from './NotFound';

const SEARCH_TITLE = 'Bank statement analyzer for Indian banks and cards — Fynora';
const robotsTag = () => document.head.querySelector<HTMLMetaElement>('meta[name="robots"]');

describe('NotFound', () => {
  beforeEach(() => {
    document.title = SEARCH_TITLE;
  });
  afterEach(() => {
    robotsTag()?.remove();
    document.head.querySelector('link[rel="canonical"]')?.remove();
    document.title = SEARCH_TITLE;
  });

  it('says the page is missing and links to the homepage, the Help Center and Contact', () => {
    const { container } = render(<MemoryRouter initialEntries={['/nope']}><NotFound /></MemoryRouter>);
    expect(container.querySelector('h1')?.textContent).toBe(NOT_FOUND_TITLE);
    for (const href of ['/', '/help', '/contact']) {
      expect(container.querySelector(`main a[href="${href}"]`), href).not.toBeNull();
    }
  });

  it('sets a noindex robots meta and the tab title while mounted, and puts both back on unmount', () => {
    const { unmount } = render(<MemoryRouter initialEntries={['/nope']}><NotFound /></MemoryRouter>);
    expect(robotsTag()?.getAttribute('content')).toBe('noindex');
    expect(document.title).toBe(NOT_FOUND_DOCUMENT_TITLE);
    unmount();
    // Both NotFound (useDocumentTitle) and PublicLayout set the title; whichever cleanup runs last
    // must still leave the original in place.
    expect(robotsTag()).toBeNull();
    expect(document.title).toBe(SEARCH_TITLE);
  });

  it('names no canonical: the URL it is reached at is whatever was typed', () => {
    render(<MemoryRouter initialEntries={['/nope']}><NotFound /></MemoryRouter>);
    expect(document.head.querySelector('link[rel="canonical"]')).toBeNull();
  });
});
