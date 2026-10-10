import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useCanonical } from './useCanonical';
import { PublicLayout } from '../components/PublicLayout';

function Probe({ path }: { path: string | null }) {
  useCanonical(path);
  return null;
}

const links = () => document.head.querySelectorAll('link[rel="canonical"]');
const ogUrls = () => [...document.head.querySelectorAll('meta[property="og:url"]')].map((m) => m.getAttribute('content'));

describe('useCanonical', () => {
  afterEach(() => {
    links().forEach((l) => l.remove());
    document.head.querySelectorAll('meta[property="og:url"]').forEach((m) => m.remove());
    vi.unstubAllEnvs();
  });

  it('adds an absolute canonical while mounted and removes it after', () => {
    const { unmount } = render(<Probe path="/terms" />);
    expect(links()).toHaveLength(1);
    expect(links()[0].getAttribute('href')).toBe('https://app.fynora.net/terms');
    unmount();
    expect(links()).toHaveLength(0);
  });

  it('follows a path change without leaving a second tag behind', () => {
    const { rerender } = render(<Probe path="/terms" />);
    rerender(<Probe path="/privacy" />);
    expect(links()).toHaveLength(1);
    expect(links()[0].getAttribute('href')).toBe('https://app.fynora.net/privacy');
  });

  it('reuses a canonical already in the head (the prerendered one) rather than adding a second', () => {
    const prerendered = document.createElement('link');
    prerendered.rel = 'canonical';
    prerendered.href = 'https://app.fynora.net/terms';
    document.head.appendChild(prerendered);

    render(<Probe path="/terms" />);
    expect(links()).toHaveLength(1);
    expect(links()[0]).toBe(prerendered);
    expect(links()[0].getAttribute('href')).toBe('https://app.fynora.net/terms');
  });

  it("removes the homepage's prerendered canonical when the visitor leaves for a page that names none", () => {
    // dist/index.html carries the homepage's canonical (scripts/prerender.mjs), and Landing calls
    // useCanonical('/'). Opening "/" and going on to /auth or into /app unmounts Landing; nothing
    // on those pages calls this hook, so a tag left behind, or put back, would still say "/".
    const prerendered = document.createElement('link');
    prerendered.rel = 'canonical';
    prerendered.href = 'https://app.fynora.net/';
    document.head.appendChild(prerendered);

    const home = render(<Probe path="/" />);
    expect(links()).toHaveLength(1);
    expect(links()[0]).toBe(prerendered);
    expect(links()[0].getAttribute('href')).toBe('https://app.fynora.net/');

    home.unmount();
    expect(links()).toHaveLength(0);
  });

  it("names each page in turn, never the first page's address on a later one", () => {
    // In-app navigation from a prerendered /terms to the homepage and on to a page with no
    // canonical. Putting the previous value back on unmount left "/terms" in the head at the end.
    const prerendered = document.createElement('link');
    prerendered.rel = 'canonical';
    prerendered.href = 'https://app.fynora.net/terms';
    document.head.appendChild(prerendered);

    const terms = render(<Probe path="/terms" />);
    terms.unmount();
    const home = render(<Probe path="/" />);
    expect(links()).toHaveLength(1);
    expect(links()[0].getAttribute('href')).toBe('https://app.fynora.net/');
    home.unmount();
    expect(links()).toHaveLength(0);
  });

  it("takes out another page's prerendered canonical when the page shown names no address (null)", () => {
    // Production answers //terms with terms.html, whose head has the /terms canonical, and React
    // Router then shows the not-found page, which is noindex. Left alone, the head said both.
    const prerendered = document.createElement('link');
    prerendered.rel = 'canonical';
    prerendered.href = 'https://app.fynora.net/terms';
    document.head.appendChild(prerendered);

    const { unmount } = render(<Probe path={null} />);
    expect(links()).toHaveLength(0);
    unmount();
    expect(links()).toHaveLength(0);
  });

  it('adds nothing for null when there is no tag to begin with', () => {
    const { unmount } = render(<Probe path={null} />);
    expect(links()).toHaveLength(0);
    unmount();
    expect(links()).toHaveLength(0);
  });

  it('the not-found page built on PublicLayout removes the canonical of the file it is shown over', () => {
    const prerendered = document.createElement('link');
    prerendered.rel = 'canonical';
    prerendered.href = 'https://app.fynora.net/terms';
    document.head.appendChild(prerendered);

    render(
      <MemoryRouter initialEntries={['//terms']}>
        <PublicLayout title="Page not found" noindex>body</PublicLayout>
      </MemoryRouter>
    );
    expect(links()).toHaveLength(0);
    expect(document.head.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('noindex');
  });

  it('is set by every page built on PublicLayout, from its own route', () => {
    render(
      <MemoryRouter initialEntries={['/cookie-policy']}>
        <PublicLayout title="Cookie Policy">body</PublicLayout>
      </MemoryRouter>
    );
    expect(links()).toHaveLength(1);
    expect(links()[0].getAttribute('href')).toBe('https://app.fynora.net/cookie-policy');
  });

  describe('og:url, the same address for link previews', () => {
    const arriveWithOgUrl = (url: string) => {
      const tag = document.createElement('meta');
      tag.setAttribute('property', 'og:url');
      tag.setAttribute('content', url);
      document.head.appendChild(tag);
      return tag;
    };

    it('is set beside the canonical while mounted and removed after', () => {
      const { unmount } = render(<Probe path="/terms" />);
      expect(ogUrls()).toEqual(['https://app.fynora.net/terms']);
      expect(links()[0].getAttribute('href')).toBe(ogUrls()[0]);
      unmount();
      expect(ogUrls()).toEqual([]);
    });

    it('reuses the one the prerendered file has, and names each page in turn', () => {
      // It was static only: after a move inside the app it went on naming the first page.
      const prerendered = arriveWithOgUrl('https://app.fynora.net/terms');
      const terms = render(<Probe path="/terms" />);
      expect(document.head.querySelector('meta[property="og:url"]')).toBe(prerendered);
      terms.unmount();
      const home = render(<Probe path="/" />);
      expect(ogUrls()).toEqual(['https://app.fynora.net/']);
      home.unmount();
      expect(ogUrls()).toEqual([]);
    });

    it('is taken out for a page that names no address (null)', () => {
      arriveWithOgUrl('https://app.fynora.net/terms');
      render(<Probe path={null} />);
      expect(ogUrls()).toEqual([]);
    });

    it('follows the page on a non-production build too, where the canonical is left alone', () => {
      // Preview files keep og:url (crawlPolicy strips only the canonical), so a stale one is
      // possible there as well. It is not an indexing signal, so nothing argues for skipping it.
      vi.stubEnv('VITE_API_BASE_URL', 'https://dev-api.fynora.net');
      arriveWithOgUrl('https://app.fynora.net/terms');
      const { unmount } = render(<Probe path="/privacy" />);
      expect(ogUrls()).toEqual(['https://app.fynora.net/privacy']);
      expect(links()).toHaveLength(0);
      unmount();
      expect(ogUrls()).toEqual([]);
    });
  });
});
