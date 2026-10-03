import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { OpenInAppBanner } from './OpenInAppBanner';
import { DISMISSED_KEY, androidOpenAppUrl } from '../lib/openInApp';

const ANDROID_CHROME =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36';
const IPHONE_SAFARI =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <OpenInAppBanner />
    </MemoryRouter>
  );
}

function useUserAgent(ua: string) {
  vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue(ua);
}

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe('OpenInAppBanner', () => {
  it('shows nothing in the shipped state, with the switch unset', () => {
    vi.stubEnv('VITE_OPEN_IN_APP_ANDROID', '');
    useUserAgent(ANDROID_CHROME);
    const { container } = renderAt('/');
    expect(container).toBeEmptyDOMElement();
  });

  it('switched on, offers to open the app on an Android browser', () => {
    vi.stubEnv('VITE_OPEN_IN_APP_ANDROID', 'true');
    useUserAgent(ANDROID_CHROME);
    renderAt('/');
    const region = screen.getByRole('region', { name: 'Open in the Fynora app' });
    expect(region).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open app' })).toHaveAttribute('href', androidOpenAppUrl());
  });

  it('switched on, shows nothing on an iPhone or inside the signed-in app pages', () => {
    vi.stubEnv('VITE_OPEN_IN_APP_ANDROID', 'true');
    useUserAgent(IPHONE_SAFARI);
    expect(renderAt('/').container).toBeEmptyDOMElement();

    useUserAgent(ANDROID_CHROME);
    expect(renderAt('/app').container).toBeEmptyDOMElement();
    expect(renderAt('/app/dashboard').container).toBeEmptyDOMElement();
    // A public page whose path merely starts with "app" is still public.
    expect(renderAt('/applications').container).not.toBeEmptyDOMElement();
  });

  it('stays closed on this device once closed', () => {
    vi.stubEnv('VITE_OPEN_IN_APP_ANDROID', 'true');
    useUserAgent(ANDROID_CHROME);
    const first = renderAt('/');
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(first.container).toBeEmptyDOMElement();
    expect(localStorage.getItem(DISMISSED_KEY)).toBe('1');

    first.unmount();
    expect(renderAt('/terms').container).toBeEmptyDOMElement();
  });
});
