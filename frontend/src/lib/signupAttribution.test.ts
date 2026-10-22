import { beforeEach, describe, expect, it } from 'vitest';
import {
  MAX_VALUE_LENGTH,
  SIGNUP_ATTRIBUTION_KEY,
  attributionFromLanding,
  captureSignupAttribution,
  readSignupAttribution,
  referrerHost,
} from './signupAttribution';

function fakeWindow(opts: { referrer?: string; search?: string; host?: string; store?: Storage | null | 'throws' } = {}) {
  const store = opts.store === undefined ? window.sessionStorage : opts.store;
  return {
    get sessionStorage(): Storage {
      if (store === 'throws') throw new Error('SecurityError');
      return store as Storage;
    },
    location: { host: opts.host ?? 'app.fynora.net', search: opts.search ?? '' },
    document: { referrer: opts.referrer ?? '' },
  };
}

describe('referrerHost', () => {
  it('keeps only the lower-cased host of the referring page, never its path or query', () => {
    expect(referrerHost('https://www.Example.com/some/path?token=abc#frag', 'app.fynora.net')).toBe('www.example.com');
    expect(referrerHost('http://news.example.org:8080/', 'app.fynora.net')).toBe('news.example.org');
  });

  it('is undefined for no referrer, an unparsable one, or this site itself', () => {
    expect(referrerHost('', 'app.fynora.net')).toBeUndefined();
    expect(referrerHost('not a url', 'app.fynora.net')).toBeUndefined();
    expect(referrerHost('https://app.fynora.net/auth', 'app.fynora.net')).toBeUndefined();
    expect(referrerHost('https://APP.fynora.net/', 'app.fynora.net')).toBeUndefined();
    // The port is ignored on both sides: a local dev server linking to itself is still itself.
    expect(referrerHost('http://localhost:5173/', 'localhost:5173')).toBeUndefined();
    expect(referrerHost('http://localhost/', 'localhost:5173')).toBeUndefined();
  });

  it('caps the host at the backend limit', () => {
    const long = `${'a'.repeat(150)}.example.com`;
    expect(referrerHost(`https://${long}/`, 'app.fynora.net')).toHaveLength(MAX_VALUE_LENGTH);
  });
});

describe('attributionFromLanding', () => {
  it('reads the four utm tags and the referrer host', () => {
    expect(attributionFromLanding(
      'https://instagram.com/reel/abc',
      '?utm_source=instagram&utm_medium=reel&utm_campaign=launch-oct&utm_content=video-a&other=ignored',
      'app.fynora.net',
    )).toEqual({
      referrerHost: 'instagram.com',
      utmSource: 'instagram',
      utmMedium: 'reel',
      utmCampaign: 'launch-oct',
      utmContent: 'video-a',
    });
  });

  it('leaves out what is missing, blank, or only whitespace', () => {
    expect(attributionFromLanding('', '?utm_source=&utm_medium=%20%20&utm_term=x', 'app.fynora.net')).toEqual({});
    expect(attributionFromLanding('', '?utm_campaign=  reel  ', 'app.fynora.net')).toEqual({ utmCampaign: 'reel' });
  });

  it('caps every value at the backend limit', () => {
    const long = 'x'.repeat(MAX_VALUE_LENGTH + 30);
    const out = attributionFromLanding('', `?utm_source=${long}`, 'app.fynora.net');
    expect(out.utmSource).toHaveLength(MAX_VALUE_LENGTH);
  });
});

describe('captureSignupAttribution / readSignupAttribution', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it('stores the first landing in sessionStorage and reads it back', () => {
    captureSignupAttribution(fakeWindow({ referrer: 'https://news.example.org/post', search: '?utm_source=news' }));

    expect(window.sessionStorage.getItem(SIGNUP_ATTRIBUTION_KEY)).toBe(
      JSON.stringify({ referrerHost: 'news.example.org', utmSource: 'news' }),
    );
    expect(readSignupAttribution(fakeWindow())).toEqual({ referrerHost: 'news.example.org', utmSource: 'news' });
    // Never localStorage, never a cookie: it must not outlive the tab.
    expect(window.localStorage.getItem(SIGNUP_ATTRIBUTION_KEY)).toBeNull();
    expect(document.cookie).not.toContain('signup-attribution');
  });

  it('lets a later arrival that carries a referrer or a tag replace an earlier capture', () => {
    captureSignupAttribution(fakeWindow({ referrer: 'https://news.example.org/post' }));
    captureSignupAttribution(fakeWindow({ referrer: 'https://other.example.com/', search: '?utm_source=other' }));

    expect(readSignupAttribution(fakeWindow())).toEqual({ referrerHost: 'other.example.com', utmSource: 'other' });
  });

  it('keeps the earlier capture when a later landing says nothing: typed in, a reload of an in-app page, a bookmark', () => {
    captureSignupAttribution(fakeWindow({ search: '?utm_source=instagram&utm_medium=reel' }));

    captureSignupAttribution(fakeWindow());
    captureSignupAttribution(fakeWindow({ referrer: 'https://app.fynora.net/auth' }));
    captureSignupAttribution(fakeWindow({ search: '?utm_source=&utm_term=ignored' }));

    expect(readSignupAttribution(fakeWindow())).toEqual({ utmSource: 'instagram', utmMedium: 'reel' });
  });

  it('writes nothing for a direct visit', () => {
    captureSignupAttribution(fakeWindow());
    expect(window.sessionStorage.getItem(SIGNUP_ATTRIBUTION_KEY)).toBeNull();
    expect(readSignupAttribution(fakeWindow())).toBeUndefined();
  });

  it('returns undefined and throws nothing when storage is missing, blocked, or holds junk', () => {
    expect(() => captureSignupAttribution(fakeWindow({ store: null, referrer: 'https://x.example/' }))).not.toThrow();
    expect(() => captureSignupAttribution(fakeWindow({ store: 'throws', referrer: 'https://x.example/' }))).not.toThrow();
    expect(readSignupAttribution(fakeWindow({ store: null }))).toBeUndefined();
    expect(readSignupAttribution(fakeWindow({ store: 'throws' }))).toBeUndefined();

    window.sessionStorage.setItem(SIGNUP_ATTRIBUTION_KEY, 'not json');
    expect(readSignupAttribution(fakeWindow())).toBeUndefined();
    window.sessionStorage.setItem(SIGNUP_ATTRIBUTION_KEY, '"a string"');
    expect(readSignupAttribution(fakeWindow())).toBeUndefined();
    window.sessionStorage.setItem(SIGNUP_ATTRIBUTION_KEY, JSON.stringify({ utmSource: 42, extra: 'dropped', utmMedium: 'reel' }));
    expect(readSignupAttribution(fakeWindow())).toEqual({ utmMedium: 'reel' });
  });
});
