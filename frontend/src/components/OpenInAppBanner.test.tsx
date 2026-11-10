import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { OpenInAppBanner } from './OpenInAppBanner';
import { DISMISSED_KEY, androidOpenAppUrl } from '../lib/openInApp';
import { playStoreLink } from '../lib/playStoreLink';

const ANDROID_CHROME =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36';
const DESKTOP_CHROME =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36';
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
  document.body.style.paddingBottom = '';
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe('OpenInAppBanner', () => {
  it('shows nothing when switched off with VITE_OPEN_IN_APP_ANDROID=false', () => {
    vi.stubEnv('VITE_OPEN_IN_APP_ANDROID', 'false');
    useUserAgent(ANDROID_CHROME);
    const { container } = renderAt('/banks/hdfc-bank-statement');
    expect(container).toBeEmptyDOMElement();
  });

  it('is on by default: offers the app on an Android browser, falling back to Play with the page referrer', () => {
    vi.stubEnv('VITE_OPEN_IN_APP_ANDROID', '');
    useUserAgent(ANDROID_CHROME);
    renderAt('/banks/hdfc-bank-statement');
    const region = screen.getByRole('region', { name: 'Get the Fynora app' });
    expect(region).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Get the app' })).toHaveAttribute(
      'href',
      androidOpenAppUrl(playStoreLink('/banks/hdfc-bank-statement', 'banner')),
    );
  });

  it('is fixed to the bottom on a prerendered page, padding the page instead of pushing it down', () => {
    useUserAgent(ANDROID_CHROME);
    const view = renderAt('/help/how-do-i-import');
    expect(screen.getByRole('region', { name: 'Get the Fynora app' })).toHaveClass('fixed', 'bottom-0');
    expect(document.body.style.paddingBottom).not.toBe('');
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(document.body.style.paddingBottom).toBe('');
    view.unmount();
  });

  it('sits in the flow at the top of the homepage, where the landing bar is fixed to the bottom', () => {
    useUserAgent(ANDROID_CHROME);
    renderAt('/');
    expect(screen.getByRole('region', { name: 'Get the Fynora app' })).not.toHaveClass('fixed');
    expect(document.body.style.paddingBottom).toBe('');
  });

  it('shows nothing on a computer, an iPhone or inside the signed-in app pages', () => {
    useUserAgent(DESKTOP_CHROME);
    expect(renderAt('/banks/hdfc-bank-statement').container).toBeEmptyDOMElement();
    useUserAgent(IPHONE_SAFARI);
    expect(renderAt('/').container).toBeEmptyDOMElement();
    expect(renderAt('/banks/hdfc-bank-statement').container).toBeEmptyDOMElement();

    useUserAgent(ANDROID_CHROME);
    expect(renderAt('/app').container).toBeEmptyDOMElement();
    expect(renderAt('/app/dashboard').container).toBeEmptyDOMElement();
    // A public page whose path merely starts with "app" is still public.
    expect(renderAt('/applications').container).not.toBeEmptyDOMElement();
  });

  it('shows nothing on a page an emailed link opens with a one-time token', () => {
    useUserAgent(ANDROID_CHROME);
    for (const path of ['/reset-password?token=t', '/verify-email', '/email-change-verify', '/verify-phone', '/reset-password/']) {
      const { container, unmount } = renderAt(path);
      expect(container, path).toBeEmptyDOMElement();
      unmount();
    }
    expect(renderAt('/auth').container).not.toBeEmptyDOMElement();
  });

  it('stays closed on this device once closed', () => {
    useUserAgent(ANDROID_CHROME);
    const first = renderAt('/');
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(first.container).toBeEmptyDOMElement();
    expect(localStorage.getItem(DISMISSED_KEY)).toBe('1');

    first.unmount();
    expect(renderAt('/terms').container).toBeEmptyDOMElement();
  });
});

describe('OpenInAppBanner with storage blocked', () => {
  it('still shows, and closing it still hides it, when localStorage throws', () => {
    useUserAgent(ANDROID_CHROME);
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
    const view = renderAt('/bank-statement-to-excel');
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(view.container).toBeEmptyDOMElement();
  });
});
