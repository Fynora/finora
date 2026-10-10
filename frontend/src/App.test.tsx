import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, waitFor } from '@testing-library/react';
import App from './App';

// App mounts the whole provider stack; nothing here should reach the network.
vi.mock('./api/endpoints', () => ({
  authApi: { login: vi.fn(), logout: vi.fn(), register: vi.fn(), refresh: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

// Preventive, not currently load-bearing: no test here reaches a chart today, because Dashboard
// and Investments (the only two modules importing react-chartjs-2) are lazy() and sit behind
// ProtectedRoute, and these tests have no session. But this file renders the whole routed app, so
// it is the one place where adding a test that mocks auth and lands on an authenticated route
// would silently mount a live Chart.js instance -- and in jsdom that instance is built with
// canvas === null, so its first update() throws uncaught and unmounts the entire React root
// mid-test (see the long note in Dashboard.test.tsx). Cheaper to hold the line here than to
// rediscover that as an intermittent failure in an unrelated test.
vi.mock('react-chartjs-2', () => ({
  Line: () => <div data-testid="line-chart" />,
  Doughnut: () => <div data-testid="doughnut-chart" />,
}));

/**
 * Regression tests for the catch-all route. <Routes> once had none, so any unmatched path rendered
 * null -- a completely blank white page, verified in a browser as #root with empty innerHTML. The
 * catch-all was then a redirect to "/", which handed a crawler following a dead link the homepage,
 * with HTTP 200, at the dead URL (a soft 404). It is now a real not-found page that keeps the URL
 * and says noindex.
 *
 * These tests cover what React renders once the bundle is running, which is all jsdom can show.
 * Which document and status an address gets BEFORE that is Cloudflare Pages' decision in
 * production (a file, a rewrite in public/_redirects, or the build's 404.html with HTTP 404), and
 * is held by scripts/spaShell.test.ts and measured on a Pages preview, not here.
 */
describe('App routing — unmatched paths', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/');
  });

  afterEach(() => {
    document.head.querySelector('meta[name="robots"]')?.remove();
  });

  it.each([
    '/definitely-not-a-page',
    '/app/transactions/extra/segments',
    // Gmail sync is paused (lib/features.ts): its review page has no route, so it behaves like any
    // unknown path. A real protected route would send a signed-out visitor to sign-in instead, so
    // the not-found page is what shows the route is genuinely gone, not merely guarded.
    '/app/settings/gmail/review',
  ])('shows the not-found page at %s, keeping the URL, with a noindex robots meta', async (path) => {
    window.history.pushState({}, '', path);

    const { container } = render(<App />);

    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('Page not found'));
    // The URL stays as typed, so the visitor can see and correct it. A redirect to "/" is exactly
    // what the SEO audit called a soft 404.
    expect(window.location.pathname).toBe(path);
    expect(document.title).toBe('Page not found — Fynora');
    expect(document.head.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('noindex');
    expect(document.head.querySelector('link[rel="canonical"]')).toBeNull();
    for (const href of ['/', '/help', '/contact']) {
      expect(container.querySelector(`main a[href="${href}"]`), href).not.toBeNull();
    }
  });

  it('leaves a route that does exist alone', async () => {
    window.history.pushState({}, '', '/terms');

    const { container } = render(<App />);

    await waitFor(() => expect(container.querySelector('h1')).not.toBeNull());
    expect(window.location.pathname).toBe('/terms');
    expect(container.querySelector('h1')?.textContent).not.toBe('Page not found');
  });

  it('renders /About as the About page, since routes match case-insensitively, with the /about canonical', async () => {
    // Cloudflare Pages matches files case-sensitively, so /About does not get about.html: it gets
    // the build's 404.html, with HTTP 404. React Router then matches the /about route regardless
    // of case and mounts the About page over it. So a browser ends up on the real page, and its
    // canonical must point at the sitemap's lower-case URL.
    window.history.pushState({}, '', '/About');

    const { container } = render(<App />);

    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('About Fynora'));
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe('https://app.fynora.net/about');
    expect(document.head.querySelector('meta[name="robots"]')).toBeNull();
  });

  it('still sends a signed-out visitor on a protected route to sign-in, not to the not-found page', async () => {
    // ProtectedRoute's <Navigate to="/auth"> must win over the catch-all: /app is a real route,
    // guarded, and /auth is a real route it redirects to.
    window.history.pushState({}, '', '/app');

    const { container } = render(<App />);

    await waitFor(() => expect(window.location.pathname).toBe('/auth'));
    await waitFor(() => expect(container.textContent?.trim()).not.toBe(''));
    expect(container.querySelector('h1')?.textContent).not.toBe('Page not found');
    expect(document.head.querySelector('meta[name="robots"]')).toBeNull();
  });

  it('removes the noindex meta and restores the title when the visitor leaves for a real page', async () => {
    window.history.pushState({}, '', '/definitely-not-a-page');
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('Page not found'));

    fireEvent.click(container.querySelector('main a[href="/help"]')!);

    await waitFor(() => expect(window.location.pathname).toBe('/help'));
    await waitFor(() => expect(container.querySelector('h1')?.textContent).not.toBe('Page not found'));
    expect(document.head.querySelector('meta[name="robots"]')).toBeNull();
    expect(document.title).not.toBe('Page not found — Fynora');
  });
});

describe('App routing — /login and /register redirect to /auth', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/');
  });

  it.each(['/login', '/register'])('redirects %s to /auth', async (path) => {
    window.history.pushState({}, '', path);
    render(<App />);
    await waitFor(() => expect(window.location.pathname).toBe('/auth'));
  });
});
