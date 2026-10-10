/**
 * Collapses runs of slashes in the address bar ("//terms" -> "/terms") before the router reads it.
 *
 * Cloudflare Pages already does this when it picks the file: //terms is answered with terms.html
 * and a 200, and // with the homepage (measured on production, 2026-10-10). React Router does not.
 * It found no route for "//terms", so the visitor was sent the terms page and then watched the
 * bundle replace it with "Page not found". Rewriting the address here, with replaceState, makes the
 * browser agree with the server: same page, and the address the page is actually known by. Nothing
 * is fetched and no history entry is added.
 *
 * Imported FIRST in main.tsx, for its side effect. Modules evaluate in import order, and some read
 * the path as they load (pages/landing/hero/firstFrame.ts asks whether it is "/"), so this has to
 * have run before App is imported, not merely before it renders.
 *
 * Only the path. A query string or fragment may hold a URL of its own (`?next=https://...`) and is
 * carried over untouched.
 */
export function collapsedPath(pathname: string): string | null {
  const collapsed = pathname.replace(/\/{2,}/g, '/');
  return collapsed === pathname ? null : collapsed;
}

export function normalizeLocation(win: Pick<Window, 'location' | 'history'>): void {
  const collapsed = collapsedPath(win.location.pathname);
  if (collapsed === null) return;
  // `collapsed` starts with exactly one slash, so it can only name a path on this origin. The
  // uncollapsed "//host/path" must never be handed to replaceState: a URL parser reads that as
  // another host.
  win.history.replaceState(win.history.state, '', collapsed + win.location.search + win.location.hash);
}

if (typeof window !== 'undefined') normalizeLocation(window);
