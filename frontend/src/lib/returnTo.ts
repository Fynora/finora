/**
 * Return-to-after-login. ProtectedRoute records the page a signed-out (or not-yet-phone-verified)
 * user asked for as router state (`{ from }`) when it redirects them to /auth or /verify-phone;
 * those pages send the user back there once they are through, instead of always to the dashboard.
 * Matters most for emailed deep links: statement-ready (/app/imports/<jobId>), security alerts
 * (/app/settings), the FINANCIAL-email opt-out line (/app/settings?tab=notifications), and the
 * email-change confirmation (/email-change-verify?sessionId=...&token=..., EmailChangeService) --
 * the one sign-in-required page outside /app, allowed by exact path only.
 *
 * Router state is not reachable from a crafted link, but it is still read back from history, so
 * every value is re-validated here before use: only a same-origin, relative path under /app (or
 * exactly /email-change-verify) is accepted -- never an absolute URL, a protocol-relative
 * `//host`, a backslash form browsers normalise into one, or any other path.
 */
export interface ReturnToState {
  from?: unknown;
}

const MAX_LENGTH = 2048;

// Sign-in-required pages outside /app that an email links to. Exact pathname match only.
const EXTRA_ALLOWED_PATHS = new Set(['/email-change-verify']);

export function safeReturnTo(value: unknown): string | null {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_LENGTH) return null;
  // Must be a plain root-relative path: one leading '/', never '//' (protocol-relative).
  if (!value.startsWith('/') || value.startsWith('//')) return null;
  // Browsers treat '\' as '/' in special-scheme URLs ('/\evil.com' is protocol-relative), and
  // control characters/whitespace are stripped before parsing -- refuse both outright.
  // eslint-disable-next-line no-control-regex
  if (/[\\\u0000-\u001f\u007f\s]/.test(value)) return null;
  let url: URL;
  try {
    url = new URL(value, window.location.origin);
  } catch {
    return null;
  }
  if (url.origin !== window.location.origin) return null;
  const inApp = url.pathname === '/app' || url.pathname.startsWith('/app/');
  if (!inApp && !EXTRA_ALLOWED_PATHS.has(url.pathname)) return null;
  return `${url.pathname}${url.search}`;
}

/** Reads and validates `from` out of a location's router state. */
export function returnToFromState(state: unknown): string | null {
  if (state === null || typeof state !== 'object') return null;
  return safeReturnTo((state as ReturnToState).from);
}
