import { render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { useRobotsNoindex } from './useRobotsNoindex';

function Probe({ enabled }: { enabled?: boolean }) {
  useRobotsNoindex(enabled);
  return null;
}

const robotsTag = () => document.head.querySelector<HTMLMetaElement>('meta[name="robots"]');

describe('useRobotsNoindex', () => {
  afterEach(() => {
    robotsTag()?.remove();
  });

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

  it('does nothing when disabled, so PublicLayout can call it unconditionally', () => {
    const { unmount } = render(<Probe enabled={false} />);
    expect(robotsTag()).toBeNull();
    unmount();
    expect(robotsTag()).toBeNull();
  });
});
