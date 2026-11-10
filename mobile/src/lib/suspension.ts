import { AUTH_ACCOUNT_SUSPENDED } from '../api/errorCodes';
import { apiErrorCode, apiErrorDetails } from './apiError';

/**
 * The suspended screen's token from a refused sign-in (privacy workstream 10), or null when the
 * error is anything else. Every sign-in path (password, email code, phone code, Google, Apple)
 * checks this before showing the refusal as an ordinary error. Mirrors the web app's
 * frontend/src/lib/suspension.ts.
 *
 * The token lasts 15 minutes and has no refresh token. It is sent per request by suspensionApi and
 * lives only in the SuspendedAccount route's params, which are never written to disk: it is never
 * the app's session and never goes into SecureStore.
 */
export function suspendedTokenFrom(err: unknown): string | null {
  if (apiErrorCode(err) !== AUTH_ACCOUNT_SUSPENDED) return null;
  const token = apiErrorDetails<{ suspendedAccessToken?: unknown }>(err)?.suspendedAccessToken;
  return typeof token === 'string' && token !== '' ? token : null;
}

/** Shown on the sign-in screen when a signed-in session is ended because the account was suspended. */
export const SUSPENDED_SIGN_OUT_MESSAGE = 'This account has been suspended. Sign in to see why and what you can do.';

/** The suspended screen's token expired (15 minutes). Whatever was typed stays on the screen. */
export const SUSPENDED_PAGE_TIMED_OUT =
  'This page timed out. Copy anything you typed, then go back and sign in again to send it.';
