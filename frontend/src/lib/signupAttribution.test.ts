import { beforeEach, describe, expect, it } from 'vitest';
import {
  LANDING_PATH_META,
  MAX_LANDING_PATH_LENGTH,
  MAX_VALUE_LENGTH,
  SIGNUP_ATTRIBUTION_KEY,
  attributionFromLanding,
  captureSignupAttribution,
  landingPathOfDocument,
  readSignupAttribution,
  referrerHost,
  validLandingPath,
} from './signupAttribution';

/** `landing` is what the page's <meta name="fynora-landing-path"> says: absent on a page that is not
 *  a prerendered public page (the 404 page, /auth, /app), which is the point. */
function fakeWindow(opts: {
  referrer?: string; search?: string; host?: string; store?: Storage | null | 'throws'; landing?: string | 'throws';
  /** The address the browser is on; defaults to the marker's own route, as on a real prerendered page. */
  pathname?: string;
} = {}) {
  const store = opts.store === undefined ? window.sessionStorage : opts.store;
  return {
    get sessionStorage(): Storage {
      if (store === 'throws') throw new Error('SecurityError');
      return store as Storage;
    },
    location: {
      host: opts.host ?? 'app.fynora.net',
      search: opts.search ?? '',
      pathname: opts.pathname ?? (opts.landing && opts.landing !== 'throws' ? opts.landing : '/auth'),
    },
    document: {
      referrer: opts.referrer ?? '',
      querySelector: (selector: string) => {
        if (opts.landing === 'throws') throw new Error('no DOM');
        if (selector !== `meta[name="${LANDING_PATH_META}"]` || opts.landing === undefined) return null;
        return { getAttribute: (name: string) => (name === 'content' ? opts.landing : null) };
      },
    },
  } as unknown as Parameters<typeof captureSignupAttribution>[0];
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

describe('validLandingPath', () => {
  it('accepts plain lower-case public-page paths', () => {
    for (const ok of ['/', '/help', '/privacy', '/banks/example-bank', '/help/how-to-read_a.statement~2']) {
      expect(validLandingPath(ok), ok).toBe(ok);
    }
    const atCap = '/' + 'a'.repeat(MAX_LANDING_PATH_LENGTH - 1);
    expect(validLandingPath(atCap)).toBe(atCap);
  });

  it('rejects anything that is not a plain path: a query, fragment, host, token, case, or the empty string', () => {
    for (const bad of ['', 'help', '//help', '/help/', '/help?token=abc', '/help#top', '/Help', '/a b', '/..', '/.hidden',
      '/help/../x', 'https://app.fynora.net/help', '/someone@example.com', '/help;x=1', '/he\nlp']) {
      expect(validLandingPath(bad), bad).toBeUndefined();
    }
    expect(validLandingPath('/' + 'a'.repeat(MAX_LANDING_PATH_LENGTH))).toBeUndefined();
    expect(validLandingPath(undefined)).toBeUndefined();
    expect(validLandingPath(null)).toBeUndefined();
    expect(validLandingPath(42)).toBeUndefined();
  });
});

describe('landingPathOfDocument', () => {
  const page = (head: string) => new DOMParser().parseFromString(`<html><head>${head}</head></html>`, 'text/html');
  const marked = (route: string) => page(`<meta name="${LANDING_PATH_META}" content="${route}" />`);

  it('reads the route the build wrote into the page, and nothing from the address bar', () => {
    expect(landingPathOfDocument(marked('/help'), '/help')).toBe('/help');
    expect(landingPathOfDocument(marked('/'), '/')).toBe('/');
  });

  it('ignores a trailing slash on the address', () => {
    expect(landingPathOfDocument(marked('/help'), '/help/')).toBe('/help');
  });

  it('is undefined when the marker names another page than the address: that page\'s file was served for this address', () => {
    // The homepage's file answering a mistyped address (a single-page-app fallback), or a case variant.
    expect(landingPathOfDocument(marked('/'), '/this-page-does-not-exist')).toBeUndefined();
    expect(landingPathOfDocument(marked('/help'), '/Help')).toBeUndefined();
    expect(landingPathOfDocument(marked('/help'), '/about')).toBeUndefined();
  });

  it('is undefined for a page with no marker, an empty one, or one that is not a plain path', () => {
    expect(landingPathOfDocument(page(''), '/help')).toBeUndefined();
    expect(landingPathOfDocument(page(`<meta name="${LANDING_PATH_META}" content="" />`), '/help')).toBeUndefined();
    expect(landingPathOfDocument(page(`<meta name="${LANDING_PATH_META}" content="/help?x=1" />`), '/help?x=1')).toBeUndefined();
    expect(landingPathOfDocument(page(`<meta name="${LANDING_PATH_META}" />`), '/help')).toBeUndefined();
  });
});

describe('landing path capture', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it('a direct visit to a public page records that page, and nothing else', () => {
    captureSignupAttribution(fakeWindow({ landing: '/banks/example-bank' }));

    expect(window.sessionStorage.getItem(SIGNUP_ATTRIBUTION_KEY)).toBe(JSON.stringify({ landingPath: '/banks/example-bank' }));
    expect(readSignupAttribution(fakeWindow())).toEqual({ landingPath: '/banks/example-bank' });
  });

  it('an arrival from a search result records the referrer, the tags and the page together', () => {
    captureSignupAttribution(fakeWindow({
      referrer: 'https://www.google.com/', search: '?utm_campaign=bank-pages', landing: '/help/example',
    }));

    expect(readSignupAttribution(fakeWindow())).toEqual({
      referrerHost: 'www.google.com', utmCampaign: 'bank-pages', landingPath: '/help/example',
    });
  });

  it('the first public page wins: a later direct page load in the same tab does not replace it', () => {
    captureSignupAttribution(fakeWindow({ landing: '/help' }));
    captureSignupAttribution(fakeWindow({ landing: '/about' }));
    captureSignupAttribution(fakeWindow({ landing: '/' }));

    expect(readSignupAttribution(fakeWindow())).toEqual({ landingPath: '/help' });
  });

  it('an in-site navigation that reloads the page (own host as referrer) never replaces the landing page', () => {
    captureSignupAttribution(fakeWindow({ referrer: 'https://google.com/', landing: '/help' }));
    captureSignupAttribution(fakeWindow({ referrer: 'https://app.fynora.net/help', landing: '/about' }));

    expect(readSignupAttribution(fakeWindow())).toEqual({ referrerHost: 'google.com', landingPath: '/help' });
  });

  it('a later arrival that carries a referrer or a tag starts over, landing page included', () => {
    captureSignupAttribution(fakeWindow({ referrer: 'https://google.com/', landing: '/help' }));
    captureSignupAttribution(fakeWindow({ search: '?utm_source=instagram', landing: '/banks/example-bank' }));

    expect(readSignupAttribution(fakeWindow())).toEqual({ utmSource: 'instagram', landingPath: '/banks/example-bank' });
  });

  it('a tagged arrival on a page that is not a public landing page (/auth, 404) keeps the tags and has no landing path', () => {
    captureSignupAttribution(fakeWindow({ referrer: 'https://google.com/', landing: '/help' }));
    captureSignupAttribution(fakeWindow({ search: '?utm_source=instagram' }));

    expect(readSignupAttribution(fakeWindow())).toEqual({ utmSource: 'instagram' });
  });

  it('a page with no marker records nothing for a direct visit: /auth, /app and the 404 page are not landing pages', () => {
    captureSignupAttribution(fakeWindow());

    expect(window.sessionStorage.getItem(SIGNUP_ATTRIBUTION_KEY)).toBeNull();
    expect(readSignupAttribution(fakeWindow())).toBeUndefined();
  });

  it('a direct visit to a public page never fills the landing path of an earlier capture that has none', () => {
    // The tab was tagged on a page that is not a landing page. A later page without tags is
    // another visit's page; mixing it into the tagged capture would pair a source with a page it did not bring.
    captureSignupAttribution(fakeWindow({ search: '?utm_source=instagram' }));
    captureSignupAttribution(fakeWindow({ landing: '/help' }));

    expect(readSignupAttribution(fakeWindow())).toEqual({ utmSource: 'instagram' });
  });

  it('records nothing when the marker is for another page than the address (the homepage file served for a mistyped address)', () => {
    captureSignupAttribution(fakeWindow({ landing: '/', pathname: '/this-page-does-not-exist' }));
    expect(window.sessionStorage.getItem(SIGNUP_ATTRIBUTION_KEY)).toBeNull();

    // A tagged arrival on such an address keeps its tags but not the wrong page.
    captureSignupAttribution(fakeWindow({ search: '?utm_source=x', landing: '/', pathname: '/this-page-does-not-exist' }));
    expect(readSignupAttribution(fakeWindow())).toEqual({ utmSource: 'x' });
  });

  it('ignores a marker that is not a plain path, and a DOM that throws', () => {
    captureSignupAttribution(fakeWindow({ landing: '/help?token=abc' }));
    captureSignupAttribution(fakeWindow({ landing: 'throws' }));
    captureSignupAttribution(fakeWindow({ search: '?utm_source=x', landing: 'https://evil.example/help' }));

    expect(readSignupAttribution(fakeWindow())).toEqual({ utmSource: 'x' });
  });

  it('drops a stored landing path that is no longer a plain path, never cutting it', () => {
    window.sessionStorage.setItem(SIGNUP_ATTRIBUTION_KEY,
      JSON.stringify({ utmSource: 'x', landingPath: '/' + 'a'.repeat(MAX_LANDING_PATH_LENGTH) }));
    expect(readSignupAttribution(fakeWindow())).toEqual({ utmSource: 'x' });

    window.sessionStorage.setItem(SIGNUP_ATTRIBUTION_KEY, JSON.stringify({ landingPath: '/Help?x=1' }));
    expect(readSignupAttribution(fakeWindow())).toBeUndefined();
  });

  it('keeps the longest valid path whole: it is not cut to the 100 characters the tags are', () => {
    const long = '/' + 'a'.repeat(150);
    window.sessionStorage.setItem(SIGNUP_ATTRIBUTION_KEY, JSON.stringify({ landingPath: long }));
    expect(readSignupAttribution(fakeWindow())).toEqual({ landingPath: long });
  });

  it('never throws when storage is blocked', () => {
    expect(() => captureSignupAttribution(fakeWindow({ store: 'throws', landing: '/help' }))).not.toThrow();
    expect(() => captureSignupAttribution(fakeWindow({ store: null, landing: '/help' }))).not.toThrow();
  });
});
