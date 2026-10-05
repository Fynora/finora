import type { RefreshedPair } from '../api/client';
import { authApi, deviceTokensApi } from '../api/endpoints';
import { revokeDeviceToken } from './pushRegistration';

type Credentials = { accessToken: string | null; refreshToken: string | null };

function statusOf(error: unknown): number | undefined {
  return (error as { response?: { status?: number } } | null)?.response?.status;
}

/**
 * Sign-out's server-side clean-up: stops this device's push notifications and ends the session.
 *
 * Runs AFTER sign-out has already deleted the stored tokens, with copies taken just before. That
 * order is the point. Sign-out used to delete storage last, after these network calls, so closing
 * the app during them (or a refresh finishing during them) left a usable session on the phone, and
 * the next launch signed straight back in (2026-10-05, a real phone). Nothing here reads or writes
 * storage, so whatever happens to these calls, the device is already signed out.
 *
 * The access token may have expired -- exactly the case that day, after the phone sat locked. Then
 * the push revoke is answered 401, so the refresh token is used once, in memory only, to get a token
 * it accepts. Leaving the revoke undone would keep this account's notifications (due dates,
 * balances) arriving on a signed-out phone.
 *
 * Never throws: the user is already signed out locally whatever happens here.
 */
export async function endRemoteSession(
  credentials: Credentials,
  deps: { revoke?: typeof revokeDeviceToken; latePair?: Promise<RefreshedPair | null> } = {}
): Promise<void> {
  const revoke = deps.revoke ?? revokeDeviceToken;
  let { accessToken, refreshToken } = credentials;

  // A refresh still in flight at sign-out rotates the session's token, which makes the stored copy
  // above stale: renewing with that one would read as a replayed token, and the backend answers
  // that by ending every session the user has. So wait for that refresh and use what it got.
  const late = deps.latePair ? await deps.latePair.catch(() => null) : null;
  if (late) {
    accessToken = late.token;
    refreshToken = late.refreshToken;
  }

  const renew = async () => {
    if (!refreshToken) return false;
    try {
      const renewed = await authApi.refresh(refreshToken);
      accessToken = renewed.token;
      refreshToken = renewed.refreshToken;
      return true;
    } catch {
      return false; // the session is already over server-side; nothing left to clean up with
    }
  };

  // Returns whether the server refused the token (401), so the caller can renew and try once more.
  const revokeWith = async (token: string) => {
    let refused = false;
    await revoke({
      deleteDeviceToken: async (body) => {
        try {
          await deviceTokensApi.revoke(body, token);
        } catch (error) {
          if (statusOf(error) === 401) refused = true;
          throw error;
        }
      },
    });
    return refused;
  };

  try {
    if (!accessToken) await renew();
    if (accessToken && (await revokeWith(accessToken)) && (await renew())) {
      await revokeWith(accessToken);
    }
  } catch {
    // revokeDeviceToken never throws; this only guards against that contract changing.
  }

  if (refreshToken) {
    // The backend ends the whole session, whichever token of its rotation chain this is.
    await authApi.logout(refreshToken).catch(() => {});
  }
}
