import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  PLAY_STORE_URL,
  androidOpenAppUrl,
  androidOpenInAppEnabled,
  iosSmartAppBannerTag,
  isAndroidBrowser,
} from './openInApp';

const ANDROID_CHROME =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36';
const ANDROID_WEBVIEW =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0 Mobile Safari/537.36';
const ANDROID_SAMSUNG =
  'Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0 Mobile Safari/537.36';
const ANDROID_FIREFOX = 'Mozilla/5.0 (Android 14; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0';
const IPHONE_SAFARI =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';
const DESKTOP_CHROME =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36';

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('openInApp', () => {
  it('is off unless VITE_OPEN_IN_APP_ANDROID is exactly "true"', () => {
    vi.stubEnv('VITE_OPEN_IN_APP_ANDROID', '');
    expect(androidOpenInAppEnabled()).toBe(false);
    vi.stubEnv('VITE_OPEN_IN_APP_ANDROID', 'TRUE');
    expect(androidOpenInAppEnabled()).toBe(false);
    vi.stubEnv('VITE_OPEN_IN_APP_ANDROID', 'true');
    expect(androidOpenInAppEnabled()).toBe(true);
  });

  it('recognises a Chromium browser on Android, but not Firefox, an app WebView, an iPhone or a computer', () => {
    expect(isAndroidBrowser(ANDROID_CHROME)).toBe(true);
    expect(isAndroidBrowser(ANDROID_SAMSUNG)).toBe(true);
    expect(isAndroidBrowser(ANDROID_FIREFOX)).toBe(false);
    expect(isAndroidBrowser(ANDROID_WEBVIEW)).toBe(false);
    expect(isAndroidBrowser(IPHONE_SAFARI)).toBe(false);
    expect(isAndroidBrowser(DESKTOP_CHROME)).toBe(false);
  });

  it('opens the production app by package and scheme, falling back to its Play Store listing', () => {
    const url = androidOpenAppUrl();
    expect(url).toBe(
      'intent://#Intent;scheme=finora;package=com.fynora.android;'
        + `S.browser_fallback_url=${encodeURIComponent(PLAY_STORE_URL)};end`
    );
    expect(PLAY_STORE_URL).toBe('https://play.google.com/store/apps/details?id=com.fynora.android');
  });

  it('builds the Smart App Banner tag only for an App Store id', () => {
    expect(iosSmartAppBannerTag('1234567890')).toBe('<meta name="apple-itunes-app" content="app-id=1234567890" />'); // synthetic-ok
    expect(iosSmartAppBannerTag(' 1234567890 ')).toBe('<meta name="apple-itunes-app" content="app-id=1234567890" />'); // synthetic-ok
    for (const notAnId of [undefined, '', '   ', 'com.fynora.app', '12345', '1234567890" onload="x']) { // synthetic-ok
      expect(iosSmartAppBannerTag(notAnId)).toBeNull();
    }
  });
});
