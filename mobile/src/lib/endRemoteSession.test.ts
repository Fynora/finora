import { authApi, deviceTokensApi } from '../api/endpoints';
import { endRemoteSession } from './endRemoteSession';
import type { revokeDeviceToken } from './pushRegistration';

jest.mock('../api/endpoints', () => ({
  authApi: { refresh: jest.fn(), logout: jest.fn() },
  deviceTokensApi: { revoke: jest.fn() },
}));
jest.mock('./pushRegistration', () => ({ revokeDeviceToken: jest.fn() }));

const refresh = authApi.refresh as jest.Mock;
const logout = authApi.logout as jest.Mock;
const revokeApi = deviceTokensApi.revoke as jest.Mock;

/** Stands in for revokeDeviceToken: same shape, and the same "never throws" contract. */
const revoke: typeof revokeDeviceToken = async (deps = {}) => {
  try {
    await deps.deleteDeviceToken!({ token: 'fcm-token' });
  } catch {
    // swallowed, like the real one
  }
};

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
    await endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { revoke });

    expect(revokeApi).toHaveBeenCalledWith({ token: 'fcm-token' }, 'A1');
    expect(refresh).not.toHaveBeenCalled();
    expect(logout).toHaveBeenCalledWith('R1');
  });

  it('THE INCIDENT: an expired access token is renewed once, in memory, so push is still revoked', async () => {
    revokeApi.mockRejectedValueOnce(unauthorized()).mockResolvedValueOnce(null);
    refresh.mockResolvedValue({ token: 'A2', refreshToken: 'R2' });

    await endRemoteSession({ accessToken: 'A1-expired', refreshToken: 'R1' }, { revoke });

    expect(refresh).toHaveBeenCalledWith('R1');
    expect(revokeApi.mock.calls.map((c) => c[1])).toEqual(['A1-expired', 'A2']);
    // Logout names the token the renewal just issued, so the session it belongs to ends.
    expect(logout).toHaveBeenCalledWith('R2');
  });

  it('a renewal the server refuses still ends with a logout and no second revoke', async () => {
    revokeApi.mockRejectedValueOnce(unauthorized());
    refresh.mockRejectedValue(unauthorized());

    await endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { revoke });

    expect(revokeApi).toHaveBeenCalledTimes(1);
    expect(logout).toHaveBeenCalledWith('R1');
  });

  it('a revoke failing for another reason is not retried with a renewal', async () => {
    revokeApi.mockRejectedValueOnce(Object.assign(new Error('offline'), { code: 'ERR_NETWORK' }));

    await endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { revoke });

    expect(refresh).not.toHaveBeenCalled();
    expect(revokeApi).toHaveBeenCalledTimes(1);
    expect(logout).toHaveBeenCalledWith('R1');
  });

  it('with no access token stored, renews first rather than revoking unauthenticated', async () => {
    refresh.mockResolvedValue({ token: 'A2', refreshToken: 'R2' });

    await endRemoteSession({ accessToken: null, refreshToken: 'R1' }, { revoke });

    expect(revokeApi).toHaveBeenCalledWith({ token: 'fcm-token' }, 'A2');
    expect(logout).toHaveBeenCalledWith('R2');
  });

  it('with nothing stored, calls nothing', async () => {
    await endRemoteSession({ accessToken: null, refreshToken: null }, { revoke });

    expect(revokeApi).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(logout).not.toHaveBeenCalled();
  });

  it('uses the pair a refresh in flight at sign-out ended up with, never renewing a stale token', async () => {
    // The stored R1 was rotated by that refresh. Renewing with it would be a replay, and the backend
    // ends every session the user has when it sees one.
    revokeApi.mockResolvedValue(null);
    const latePair = Promise.resolve({ token: 'A2', refreshToken: 'R2' });

    await endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { revoke, latePair });

    expect(revokeApi).toHaveBeenCalledWith({ token: 'fcm-token' }, 'A2');
    expect(refresh).not.toHaveBeenCalled();
    expect(logout).toHaveBeenCalledWith('R2');
  });

  it('falls back to the stored pair when the in-flight refresh got nothing', async () => {
    await endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { revoke, latePair: Promise.resolve(null) });

    expect(revokeApi).toHaveBeenCalledWith({ token: 'fcm-token' }, 'A1');
    expect(logout).toHaveBeenCalledWith('R1');
  });

  it('never throws, even when logout fails', async () => {
    logout.mockRejectedValue(new Error('server down'));

    await expect(endRemoteSession({ accessToken: 'A1', refreshToken: 'R1' }, { revoke })).resolves.toBeUndefined();
  });
});
