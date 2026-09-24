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

  it('reuses and then restores a canonical already in the head (the prerendered one)', () => {
    const prerendered = document.createElement('link');
    prerendered.rel = 'canonical';
    prerendered.href = 'https://app.fynora.net/terms';
    document.head.appendChild(prerendered);

    const { unmount } = render(<Probe path="/terms" />);
    expect(links()).toHaveLength(1);
    unmount();
    expect(links()).toHaveLength(1);
    expect(links()[0].getAttribute('href')).toBe('https://app.fynora.net/terms');
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
