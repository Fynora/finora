import type { RefreshedPair } from '../api/client';
import { authApi, deviceTokensApi } from '../api/endpoints';

type Credentials = { accessToken: string | null; refreshToken: string | null };

function statusOf(error: unknown): number | undefined {
  return (error as { response?: { status?: number } } | null)?.response?.status;
}

/**
 * Sign-out's server-side clean-up: revokes this device's push token and ends the session.
 *
 * Runs AFTER sign-out has already deleted the stored tokens, with copies taken just before. That
 * order is the point. Sign-out used to delete storage last, after these network calls, so closing
 * the app during them (or a refresh finishing during them) left a usable session on the phone, and
 * the next launch signed straight back in (2026-10-05, a real phone). Nothing here reads or writes
 * storage, so whatever happens to these calls, the device is already signed out.
 *
 * The push revoke here is the server's half only. The phone also deletes its token with Firebase
 * (pushRegistration's detachDevice), which stops the notifications even when this never runs; this
 * call just means the server stops trying straight away. `pushToken` is the token detachDevice read
 * before deleting it -- reading it here instead would mint a new one.
 *
 * The access token may have expired -- exactly the case that day, after the phone sat locked. Then
 * the push revoke is answered 401, so the refresh token is used once, in memory only, to get a token
 * it accepts.
 *
 * Never throws: the user is already signed out locally whatever happens here.
 */
export async function endRemoteSession(
  credentials: Credentials,
  deps: { pushToken?: Promise<string | null>; latePair?: Promise<RefreshedPair | null> } = {}
): Promise<void> {
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

  // Resolves to whether the server refused the access token (401), so the caller can renew once.
  const revokeWith = async (pushToken: string, token: string) => {
    try {
      await deviceTokensApi.revoke({ token: pushToken }, token);
      return false;
    } catch (error) {
      return statusOf(error) === 401;
    }
  };

  const pushToken = deps.pushToken ? await deps.pushToken.catch(() => null) : null;
  if (pushToken) {
    if (!accessToken) await renew();
    if (accessToken && (await revokeWith(pushToken, accessToken)) && (await renew())) {
      await revokeWith(pushToken, accessToken);
    }
  }

  if (refreshToken) {
    // The backend ends the whole session, whichever token of its rotation chain this is.
    await authApi.logout(refreshToken).catch(() => {});
  }
}
