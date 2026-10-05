import { authApi, deviceTokensApi } from '../api/endpoints';
import { endRemoteSession } from './endRemoteSession';

jest.mock('../api/endpoints', () => ({
  authApi: { refresh: jest.fn(), logout: jest.fn() },
  deviceTokensApi: { revoke: jest.fn() },
}));

const refresh = authApi.refresh as jest.Mock;
const logout = authApi.logout as jest.Mock;
const revokeApi = deviceTokensApi.revoke as jest.Mock;

/** The token detachDevice read before deleting it with Firebase. */
const pushToken = () => Promise.resolve('fcm-token');

function unauthorized() {
  return Object.assign(new Error('401'), { response: { status: 401 } });
}

beforeEach(() => {
  refresh.mockReset();
  logout.mockReset().mockResolvedValue({ message: 'ok' });
  revokeApi.mockReset().mockResolvedValue(null);
});

describe('endRemoteSession', () => {
  it('revokes push with the departing access token, then ends the session', async () => {
    await endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { pushToken: pushToken() });

    expect(revokeApi).toHaveBeenCalledWith({ token: 'fcm-token' }, 'A1');
    expect(refresh).not.toHaveBeenCalled();
    expect(logout).toHaveBeenCalledWith('R1');
  });

  it('THE INCIDENT: an expired access token is renewed once, in memory, so push is still revoked', async () => {
    revokeApi.mockRejectedValueOnce(unauthorized()).mockResolvedValueOnce(null);
    refresh.mockResolvedValue({ token: 'A2', refreshToken: 'R2' });

    await endRemoteSession({ accessToken: 'A1-expired', refreshToken: 'R1' }, { pushToken: pushToken() });

    expect(refresh).toHaveBeenCalledWith('R1');
    expect(revokeApi.mock.calls.map((c) => c[1])).toEqual(['A1-expired', 'A2']);
    // Logout names the token the renewal just issued, so the session it belongs to ends.
    expect(logout).toHaveBeenCalledWith('R2');
  });

  it('a renewal the server refuses still ends with a logout and no second revoke', async () => {
    revokeApi.mockRejectedValueOnce(unauthorized());
    refresh.mockRejectedValue(unauthorized());

    await endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { pushToken: pushToken() });

    expect(revokeApi).toHaveBeenCalledTimes(1);
    expect(logout).toHaveBeenCalledWith('R1');
  });

  it('a revoke failing for another reason is not retried with a renewal', async () => {
    revokeApi.mockRejectedValueOnce(Object.assign(new Error('offline'), { code: 'ERR_NETWORK' }));

    await endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { pushToken: pushToken() });

    expect(refresh).not.toHaveBeenCalled();
    expect(revokeApi).toHaveBeenCalledTimes(1);
    expect(logout).toHaveBeenCalledWith('R1');
  });

  it('with no access token stored, renews first rather than revoking unauthenticated', async () => {
    refresh.mockResolvedValue({ token: 'A2', refreshToken: 'R2' });

    await endRemoteSession({ accessToken: null, refreshToken: 'R1' }, { pushToken: pushToken() });

    expect(revokeApi).toHaveBeenCalledWith({ token: 'fcm-token' }, 'A2');
    expect(logout).toHaveBeenCalledWith('R2');
  });

  it('with no push token (never registered, or Firebase unavailable), only ends the session', async () => {
    await endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { pushToken: Promise.resolve(null) });

    expect(revokeApi).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(logout).toHaveBeenCalledWith('R1');
  });

  it('with nothing stored, calls nothing', async () => {
    await endRemoteSession({ accessToken: null, refreshToken: null }, { pushToken: pushToken() });

    expect(revokeApi).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(logout).not.toHaveBeenCalled();
  });

  it('uses the pair a refresh in flight at sign-out ended up with, never renewing a stale token', async () => {
    // The stored R1 was rotated by that refresh. Renewing with it would be a replay, and the backend
    // ends every session the user has when it sees one.
    const latePair = Promise.resolve({ token: 'A2', refreshToken: 'R2' });

    await endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { pushToken: pushToken(), latePair });

    expect(revokeApi).toHaveBeenCalledWith({ token: 'fcm-token' }, 'A2');
    expect(refresh).not.toHaveBeenCalled();
    expect(logout).toHaveBeenCalledWith('R2');
  });

  it('falls back to the stored pair when the in-flight refresh got nothing', async () => {
    await endRemoteSession(
      { accessToken: 'A1', refreshToken: 'R1' },
      { pushToken: pushToken(), latePair: Promise.resolve(null) }
    );

    expect(revokeApi).toHaveBeenCalledWith({ token: 'fcm-token' }, 'A1');
    expect(logout).toHaveBeenCalledWith('R1');
  });

  it('never throws, even when logout fails', async () => {
    logout.mockRejectedValue(new Error('server down'));

    await expect(
      endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { pushToken: pushToken() })
    ).resolves.toBeUndefined();
  });
});
