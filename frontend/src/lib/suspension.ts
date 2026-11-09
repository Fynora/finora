import { AUTH_ACCOUNT_SUSPENDED } from '../api/errorCodes';

/**
 * The suspended screen's token from a refused sign-in (privacy workstream 10), or null when the
 * error is anything else. Every sign-in path (password, one-time code, Google, Apple) checks this
 * before showing the refusal as an ordinary error.
 */
export function suspendedTokenFrom(err: any): string | null {
  const data = err?.response?.data;
  if (data?.errorCode !== AUTH_ACCOUNT_SUSPENDED) return null;
  const token = data?.details?.suspendedAccessToken;
  return typeof token === 'string' && token !== '' ? token : null;
}
