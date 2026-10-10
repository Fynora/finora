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
    // The head is written by effects, which React runs a moment after the heading is on screen.
    // Waited for, not read at once: see "the head is waited for" at the foot of this file.
    await waitFor(() => expect(document.title).toBe('Page not found — Fynora'));
    await waitFor(() =>
      expect(document.head.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('noindex'));
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
    await waitFor(() =>
      expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe('https://app.fynora.net/about'));
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
    await waitFor(() => expect(document.head.querySelector('meta[name="robots"]')).toBeNull());
    await waitFor(() => expect(document.title).toBe('Help Center — Fynora'));
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

/**
 * dist/index.html arrives with the homepage's canonical already in its head (scripts/prerender.mjs
 * adds it at build time). Landing and every PublicLayout page then manage that one tag through
 * useCanonical. These walk the real routes, because the rule that matters is about the hand-over
 * between pages: exactly one tag on a page that names an address, naming THAT page, and none on a
 * page that names no address. jsdom does not load dist/index.html, so the tag is put in the head
 * here the way the file has it.
 */
describe('App routing — the canonical the homepage arrives with', () => {
  const canonicals = () =>
    [...document.head.querySelectorAll('link[rel="canonical"]')].map((link) => link.getAttribute('href'));

  beforeEach(() => {
    const prerendered = document.createElement('link');
    prerendered.rel = 'canonical';
    prerendered.href = 'https://app.fynora.net/';
    document.head.appendChild(prerendered);
    window.history.pushState({}, '', '/');
  });

  afterEach(() => {
    document.head.querySelectorAll('link[rel="canonical"]').forEach((link) => link.remove());
  });

  it('stays the only one on the homepage, and follows the visitor to a public page and back', async () => {
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('h1')).not.toBeNull());
    // Never more than one, at any point in the walk: a second tag would be the bug even if a later
    // step removed it again.
    const seen: number[] = [];
    const watcher = new MutationObserver(() => seen.push(canonicals().length));
    watcher.observe(document.head, { childList: true });
    expect(canonicals()).toEqual(['https://app.fynora.net/']);

    fireEvent.click(container.querySelector('a[href="/terms"]')!);
    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('Terms & Conditions'));
    await waitFor(() => expect(canonicals()).toEqual(['https://app.fynora.net/terms']));

    fireEvent.click(container.querySelector('a[href="/"]')!);
    await waitFor(() => expect(window.location.pathname).toBe('/'));
    await waitFor(() => expect(canonicals()).toEqual(['https://app.fynora.net/']));
    watcher.disconnect();
    expect(Math.max(1, ...seen)).toBe(1);
  });

  it('is gone once the visitor moves on to sign-in, which names no address', async () => {
    // The case the tag in the file created: open "/", press "Sign in". Left in the head, it would
    // name the homepage as the address of /auth, and of every page under /app after that.
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('h1')).not.toBeNull());
    expect(canonicals()).toEqual(['https://app.fynora.net/']);

    fireEvent.click(container.querySelector('a[href="/auth"]')!);
    await waitFor(() => expect(window.location.pathname).toBe('/auth'));
    await waitFor(() => expect(canonicals()).toEqual([]));
  });

  it('is gone on a dead link reached from the homepage', async () => {
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('h1')).not.toBeNull());

    window.history.pushState({}, '', '/definitely-not-a-page');
    window.dispatchEvent(new PopStateEvent('popstate'));
    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('Page not found'));
    // Waited for, not read straight after the heading: React removes the old page's nodes first and
    // runs its effect cleanups a moment later, so the new heading can be on screen while the tag is
    // still there. Asserting at once failed about one run in three.
    await waitFor(() => expect(canonicals()).toEqual([]));
  });
});

/**
 * The head a visitor is in belongs to the document they opened FIRST, and that document is not
 * always the page now on screen: it is another page's file after a move inside the app, and it is
 * the blank shell or 404.html whenever Pages answered with one of those. Every case below was
 * measured in Chrome on production on 2026-10-10 before it was fixed. jsdom loads none of the
 * built files, so each test first puts the head the way that file has it.
 *
 * Why the head is waited for and never read straight after a heading: React takes the old page's
 * nodes out, and only later, in a task of its own, runs effects and their cleanups. Outside act()
 * that task is queued with setImmediate, while waitFor returns after a setTimeout(0), and Node
 * does not promise which of the two fires first. Three older tests in this file read the head at
 * once and failed for exactly that reason, about once in fifty full runs. Holding React's task back
 * 20 ms (a setup file wrapping setImmediate) made all three fail every time, and pass every time
 * once they waited.
 */
describe('App routing — the head follows the page, whichever document was opened first', () => {
  const HOME_TITLE = 'Bank statement analyzer for Indian banks and cards — Fynora';
  const SITE_DESCRIPTION =
    'Fynora reads PDF and CSV statements from Indian banks and credit cards, checks its own work, and sorts every transaction.';
  const added: Element[] = [];

  const put = (tag: 'meta' | 'link', attrs: Record<string, string>) => {
    const el = document.createElement(tag);
    for (const [name, value] of Object.entries(attrs)) el.setAttribute(name, value);
    document.head.appendChild(el);
    added.push(el);
  };
  const meta = (selector: string) => document.head.querySelector(selector)?.getAttribute('content') ?? null;
  const head = () => ({
    title: document.title,
    description: meta('meta[name="description"]'),
    ogTitle: meta('meta[property="og:title"]'),
    ogDescription: meta('meta[property="og:description"]'),
    ogUrl: meta('meta[property="og:url"]'),
    canonical: document.head.querySelector('link[rel="canonical"]')?.getAttribute('href') ?? null,
    robots: meta('meta[name="robots"]'),
  });

  /** A built document's head: the three tags every file has, plus whatever that file adds. */
  const arriveOn = (path: string, doc: { title: string; description: string; address?: string; robots?: string }) => {
    document.title = doc.title;
    put('meta', { name: 'description', content: doc.description });
    put('meta', { property: 'og:title', content: doc.title });
    put('meta', { property: 'og:description', content: doc.description });
    if (doc.address) {
      put('link', { rel: 'canonical', href: doc.address });
      put('meta', { property: 'og:url', content: doc.address });
    }
    if (doc.robots) put('meta', { name: 'robots', content: doc.robots });
    window.history.pushState({}, '', path);
  };

  const TERMS_FILE = {
    title: 'Terms & Conditions — Fynora',
    description: 'The terms page description.',
    address: 'https://app.fynora.net/terms',
  };
  const SHELL_FILE = { title: HOME_TITLE, description: SITE_DESCRIPTION, robots: 'noindex, nofollow' };
  const NOT_FOUND_FILE = { title: 'Page not found — Fynora', description: 'There is no page at this address.', robots: 'noindex' };

  afterEach(() => {
    for (const selector of ['link[rel="canonical"]', 'meta[property="og:url"]', 'meta[name="robots"]']) {
      document.head.querySelectorAll(selector).forEach((el) => el.remove());
    }
    added.splice(0).forEach((el) => el.remove());
    document.title = '';
  });

  it('opened on /terms, then the homepage inside the app: the tab and every tag are the homepage\'s', async () => {
    // Was: the tab read "Terms & Conditions — Fynora" over the homepage, with the terms page's
    // description, og:title, og:description and og:url.
    arriveOn('/terms', TERMS_FILE);
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('Terms & Conditions'));

    fireEvent.click(container.querySelector('a[href="/"]')!);
    await waitFor(() => expect(window.location.pathname).toBe('/'));
    await waitFor(() =>
      expect(head()).toEqual({
        title: HOME_TITLE,
        description: SITE_DESCRIPTION,
        ogTitle: HOME_TITLE,
        ogDescription: SITE_DESCRIPTION,
        ogUrl: 'https://app.fynora.net/',
        canonical: 'https://app.fynora.net/',
        robots: null,
      }));
  });

  it('then on to sign-in: its own title, the site\'s description, and no address', async () => {
    arriveOn('/terms', TERMS_FILE);
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('Terms & Conditions'));
    fireEvent.click(container.querySelector('a[href="/"]')!);
    await waitFor(() => expect(head().canonical).toBe('https://app.fynora.net/'));

    fireEvent.click(container.querySelector('a[href="/auth"]')!);
    await waitFor(() => expect(window.location.pathname).toBe('/auth'));
    await waitFor(() =>
      expect(head()).toEqual({
        title: 'Sign in — Fynora',
        description: SITE_DESCRIPTION,
        ogTitle: HOME_TITLE,
        ogDescription: SITE_DESCRIPTION,
        ogUrl: null,
        canonical: null,
        robots: null,
      }));
  });

  it('opened on /auth (the blank shell), then a public page: the shell\'s noindex does not come along', async () => {
    // Was: "noindex, nofollow" still in the head on the homepage and on /terms, beside the canonical.
    arriveOn('/auth', SHELL_FILE);
    const { container } = render(<App />);
    await waitFor(() => expect(document.title).toBe('Sign in — Fynora'));
    expect(head().robots).toBe('noindex, nofollow');

    // The footer link. The consent line above it also links to /terms, in a new tab.
    fireEvent.click(container.querySelector('a[href="/terms"]:not([target])')!);
    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('Terms & Conditions'));
    await waitFor(() => expect(head().robots).toBeNull());
    await waitFor(() => expect(head().canonical).toBe('https://app.fynora.net/terms'));

    fireEvent.click(container.querySelector('a[href="/"]')!);
    await waitFor(() => expect(head().canonical).toBe('https://app.fynora.net/'));
    expect(head().robots).toBeNull();
    expect(head().title).toBe(HOME_TITLE);
  });

  it('opened on /auth, then straight to the homepage: no noindex there either', async () => {
    arriveOn('/auth', SHELL_FILE);
    const { container } = render(<App />);
    await waitFor(() => expect(document.title).toBe('Sign in — Fynora'));

    fireEvent.click(container.querySelector('a[href="/"]')!);
    await waitFor(() => expect(window.location.pathname).toBe('/'));
    await waitFor(() => expect(head().robots).toBeNull());
    await waitFor(() => expect(head().title).toBe(HOME_TITLE));
  });

  it('/Auth (404.html again), then the homepage: none of the not-found file is left on it', async () => {
    // The sign-in page sets a title and nothing else, so here the homepage is the first page that
    // has to say what the description and social text are. Without its own it would keep "Page not
    // found" as og:title.
    arriveOn('/Auth', NOT_FOUND_FILE);
    const { container } = render(<App />);
    await waitFor(() => expect(document.title).toBe('Sign in — Fynora'));

    fireEvent.click(container.querySelector('a[href="/"]')!);
    await waitFor(() => expect(window.location.pathname).toBe('/'));
    await waitFor(() =>
      expect(head()).toEqual({
        title: HOME_TITLE,
        description: SITE_DESCRIPTION,
        ogTitle: HOME_TITLE,
        ogDescription: SITE_DESCRIPTION,
        ogUrl: 'https://app.fynora.net/',
        canonical: 'https://app.fynora.net/',
        robots: null,
      }));
  });

  it('/About, which Pages answers with 404.html: the real page drops that file\'s noindex and text', async () => {
    // Was: the About page with canonical /about and the not-found file's "noindex" side by side.
    arriveOn('/About', NOT_FOUND_FILE);
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('About Fynora'));
    await waitFor(() =>
      expect(head()).toMatchObject({
        title: 'About Fynora',
        ogTitle: 'About Fynora',
        ogUrl: 'https://app.fynora.net/about',
        canonical: 'https://app.fynora.net/about',
        robots: null,
      }));
    expect(head().description).not.toBe(NOT_FOUND_FILE.description);
    expect(head().ogDescription).toBe(head().description);
  });

  it('a dead address (404.html), then the Help Center: the file\'s noindex stays behind', async () => {
    arriveOn('/definitely-not-a-page', NOT_FOUND_FILE);
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('Page not found'));
    // The not-found page keeps the file's tag exactly as it is.
    await waitFor(() => expect(document.title).toBe('Page not found — Fynora'));
    expect(head().robots).toBe('noindex');
    expect(head().canonical).toBeNull();

    fireEvent.click(container.querySelector('main a[href="/help"]')!);
    await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('Help Center'));
    await waitFor(() =>
      expect(head()).toMatchObject({
        title: 'Help Center — Fynora',
        ogTitle: 'Help Center — Fynora',
        ogUrl: 'https://app.fynora.net/help',
        canonical: 'https://app.fynora.net/help',
        robots: null,
      }));
  });

  it('never touches the robots meta on a non-production build, on any page', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://dev-api.fynora.net');
    try {
      arriveOn('/auth', SHELL_FILE);
      const { container } = render(<App />);
      await waitFor(() => expect(document.title).toBe('Sign in — Fynora'));
      fireEvent.click(container.querySelector('a[href="/terms"]:not([target])')!);
      await waitFor(() => expect(container.querySelector('h1')?.textContent).toBe('Terms & Conditions'));
      await waitFor(() => expect(head().ogUrl).toBe('https://app.fynora.net/terms'));
      expect(head().robots).toBe('noindex, nofollow');
      expect(head().canonical).toBeNull();
    } finally {
      vi.unstubAllEnvs();
    }
  });
});

