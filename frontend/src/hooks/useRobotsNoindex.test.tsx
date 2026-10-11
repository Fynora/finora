import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useRobotsNoindex } from './useRobotsNoindex';

function Probe({ enabled }: { enabled?: boolean }) {
  useRobotsNoindex(enabled);
  return null;
}

const robotsTag = () => document.head.querySelector<HTMLMetaElement>('meta[name="robots"]');

describe('useRobotsNoindex', () => {
  afterEach(() => {
    robotsTag()?.remove();
    vi.unstubAllEnvs();
  });

  const arriveWith = (content: string) => {
    const tag = document.createElement('meta');
    tag.setAttribute('name', 'robots');
    tag.setAttribute('content', content);
    document.head.appendChild(tag);
    return tag;
  };

  it('adds a noindex robots meta while mounted and removes it on unmount', () => {
    expect(robotsTag()).toBeNull();
    const { unmount } = render(<Probe />);
    expect(robotsTag()?.getAttribute('content')).toBe('noindex');
    unmount();
    expect(robotsTag()).toBeNull();
  });

  it('leaves a robots meta that already says noindex untouched, so a non-production build keeps nofollow', () => {
    // scripts/crawlPolicy.mjs bakes "noindex, nofollow" into every non-production HTML file.
    const baked = document.createElement('meta');
    baked.setAttribute('name', 'robots');
    baked.setAttribute('content', 'noindex, nofollow');
    document.head.appendChild(baked);

    const { unmount } = render(<Probe />);
    expect(document.head.querySelectorAll('meta[name="robots"]')).toHaveLength(1);
    expect(baked.getAttribute('content')).toBe('noindex, nofollow');
    unmount();
    expect(baked.isConnected).toBe(true);
    expect(baked.getAttribute('content')).toBe('noindex, nofollow');
  });

  it('sets an existing robots meta that allows indexing to noindex, and restores it on unmount', () => {
    const tag = document.createElement('meta');
    tag.setAttribute('name', 'robots');
    tag.setAttribute('content', 'index, follow');
    document.head.appendChild(tag);

    const { unmount } = render(<Probe />);
    expect(document.head.querySelectorAll('meta[name="robots"]')).toHaveLength(1);
    expect(tag.getAttribute('content')).toBe('noindex');
    unmount();
    expect(tag.getAttribute('content')).toBe('index, follow');
  });

  it('adds nothing when disabled, so PublicLayout can call it for every page', () => {
    const { unmount } = render(<Probe enabled={false} />);
    expect(robotsTag()).toBeNull();
    unmount();
    expect(robotsTag()).toBeNull();
  });

  // Two production files carry a robots noindex: the blank shell for /auth and /app ("noindex,
  // nofollow") and 404.html ("noindex"). Measured in Chrome on production, 2026-10-10: open /auth,
  // follow the link to the homepage or /terms inside the app, and the tag was still in the head.
  it.each(['noindex, nofollow', 'noindex', 'NOINDEX'])(
    'on a page that should be indexed, takes a leftover "%s" out of the head, for good',
    (leftover) => {
      const tag = arriveWith(leftover);
      const { unmount } = render(<Probe enabled={false} />);
      expect(tag.isConnected).toBe(false);
      expect(robotsTag()).toBeNull();
      unmount();
      expect(robotsTag()).toBeNull();
    },
  );

  it('leaves a robots meta that allows indexing alone', () => {
    const tag = arriveWith('index, follow');
    render(<Probe enabled={false} />);
    expect(tag.isConnected).toBe(true);
    expect(tag.getAttribute('content')).toBe('index, follow');
  });

  it("never removes the tag on a non-production build, where it is the build's own policy", () => {
    // scripts/crawlPolicy.mjs writes "noindex, nofollow" into every file of a preview or dev-app
    // build, the public pages included. Removing it there would leave only the header.
    vi.stubEnv('VITE_API_BASE_URL', 'https://dev-api.fynora.net');
    const tag = arriveWith('noindex, nofollow');
    const { unmount } = render(<Probe enabled={false} />);
    expect(tag.isConnected).toBe(true);
    expect(tag.getAttribute('content')).toBe('noindex, nofollow');
    unmount();
    expect(tag.isConnected).toBe(true);
  });
});
