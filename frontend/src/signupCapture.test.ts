import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LANDING_PATH_META, SIGNUP_ATTRIBUTION_KEY } from './lib/signupAttribution';

/**
 * src/signupCapture.ts is the entry the build bundles into the early classic script. Importing it is
 * what the browser does when that script runs: the capture happens as a side effect, from the
 * page's own marker and address, with no React and no app code.
 */
describe('signupCapture (the early capture script)', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    document.head.innerHTML = '';
    vi.resetModules();
  });

  it('records the page the marker names, when the address agrees', async () => {
    document.head.innerHTML = `<meta name="${LANDING_PATH_META}" content="/" />`;
    // jsdom's address is its test origin's "/", which is what a prerendered homepage would report.
    expect(window.location.pathname).toBe('/');

    await import('./signupCapture');

    expect(window.sessionStorage.getItem(SIGNUP_ATTRIBUTION_KEY)).toBe(JSON.stringify({ landingPath: '/' }));
  });

  it('records nothing on a page with no marker', async () => {
    await import('./signupCapture');

    expect(window.sessionStorage.getItem(SIGNUP_ATTRIBUTION_KEY)).toBeNull();
  });

  it('running again, as main.tsx does on the same page load, changes nothing', async () => {
    document.head.innerHTML = `<meta name="${LANDING_PATH_META}" content="/" />`;
    await import('./signupCapture');
    const first = window.sessionStorage.getItem(SIGNUP_ATTRIBUTION_KEY);

    const { captureSignupAttribution } = await import('./lib/signupAttribution');
    captureSignupAttribution(window);

    expect(window.sessionStorage.getItem(SIGNUP_ATTRIBUTION_KEY)).toBe(first);
  });
});
