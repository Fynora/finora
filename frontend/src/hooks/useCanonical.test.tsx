import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { useCanonical } from './useCanonical';
import { PublicLayout } from '../components/PublicLayout';

function Probe({ path }: { path: string }) {
  useCanonical(path);
  return null;
}

const links = () => document.head.querySelectorAll('link[rel="canonical"]');

describe('useCanonical', () => {
  afterEach(() => {
    links().forEach((l) => l.remove());
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

  it('is set by every page built on PublicLayout, from its own route', () => {
    render(
      <MemoryRouter initialEntries={['/cookie-policy']}>
        <PublicLayout title="Cookie Policy">body</PublicLayout>
      </MemoryRouter>
    );
    expect(links()).toHaveLength(1);
    expect(links()[0].getAttribute('href')).toBe('https://app.fynora.net/cookie-policy');
  });
});
