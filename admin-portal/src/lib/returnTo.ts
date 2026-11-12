/**
 * Where to go after signing in: the page the admin was trying to open, such as the held statement
 * a hold email links to. Carried as `/login?next=<path>` so it survives the full-page redirect a
 * session end makes (api/client.ts) as well as the in-app one (ProtectedRoute).
 *
 * Only a path on this site is accepted: it must start with one "/", so `//evil.example` or a full
 * URL can never send a freshly signed-in admin somewhere else. The sign-in screens themselves are
 * refused too, so a sign-in never loops back to one.
 */
const SIGN_IN_PATHS = ['/login', '/setup', '/forgot-password', '/reset-password', '/verify-phone', '/setup-mfa'];

export function safeReturnPath(raw: string | null | undefined): string | null {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return null;
  const pathname = raw.split(/[?#]/, 1)[0];
  if (pathname === '/' || SIGN_IN_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`))) return null;
  return raw;
}

/** The login URL that brings the admin back to `path` (pathname plus search) after signing in. */
export function loginPathFor(path: string): string {
  const next = safeReturnPath(path);
  return next ? `/login?next=${encodeURIComponent(next)}` : '/login';
}
