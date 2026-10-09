/**
 * Signing out while a token refresh is in flight.
 *
 * What a real phone did on 2026-10-05: background requests 401'd on an expired access token and
 * started a refresh; the user tapped Sign out; the refresh finished a moment later and wrote a fresh
 * token pair to storage; the app was closed before sign-out's own cleanup ran, and the next launch
 * restored that session. These tests hold the refresh response open, sign out inside the window,
 * then release it -- the same harness as refreshRace.test.ts: real interceptors, real storage
 * wrapper, a fake server underneath.
 */

process.env.EXPO_PUBLIC_API_BASE_URL = 'https://tests.invalid';

const REFRESH_TOKEN_KEY = 'finora_refresh_token';
const TOKEN_KEY = 'finora_token';

jest.mock('../lib/monitoring', () => ({
  reportHandledEvent: jest.fn(),
  reportHandledError: jest.fn(),
}));

type Deferred = { promise: Promise<void>; release: () => void };
function deferred(): Deferred {
  let release!: () => void;
  const promise = new Promise<void>((resolve) => {
    release = () => resolve();
  });
  return { promise, release };
}

async function until(predicate: () => boolean, what: string, budgetMs = 2000) {
  const deadline = Date.now() + budgetMs;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for: ${what}`);
    await new Promise((r) => setTimeout(r, 1));
  }
}

describe('signing out while a refresh is in flight', () => {
  let client: typeof import('./client');
  let secureStore: { __store: Map<string, string>; setItemAsync: jest.Mock };
  let reportHandledEvent: jest.Mock;
  let onSessionExpired: jest.Mock;
  /** Refresh tokens presented to /auth/refresh, in order. */
  let presented: string[];
  /** Refresh tokens presented to /auth/logout, in order. */
  let loggedOut: string[];
  /** Set to hold the refresh RESPONSE open (the server has already rotated by then). */
  let refreshGate: Deferred | null;
  /** Per presented token, for holding one refresh open while another completes. */
  let gates: Map<string, Deferred>;

  beforeEach(() => {
    jest.resetModules();
    presented = [];
    loggedOut = [];
    refreshGate = null;
    gates = new Map();

    client = require('./client');
    secureStore = require('expo-secure-store');
    secureStore.__store.clear();
    secureStore.setItemAsync.mockClear();
    reportHandledEvent = require('../lib/monitoring').reportHandledEvent;
    reportHandledEvent.mockClear();
    onSessionExpired = jest.fn();
    client.setSessionCallbacks({ onSessionExpired });

    client.rawApi.defaults.adapter = (async (config: { url?: string; data?: string }) => {
      if (!config.url?.includes('/auth/refresh')) throw new Error(`unexpected raw call: ${config.url}`);
      const { refreshToken } = JSON.parse(config.data ?? '{}');
      presented.push(refreshToken);
      const next = `R${presented.length + 1}`;
      const gate = gates.get(refreshToken) ?? refreshGate;
      if (gate) await gate.promise;
      return {
        data: { data: { token: `access-${next}`, refreshToken: next } },
        status: 200, statusText: 'OK', headers: {}, config,
      };
    }) as never;

    client.api.defaults.adapter = (async (config: { url?: string; data?: string }) => {
      if (config.url?.includes('/auth/logout')) loggedOut.push(JSON.parse(config.data ?? '{}').refreshToken);
      return { data: { success: true, data: {} }, status: 200, statusText: 'OK', headers: {}, config };
    }) as never;
  });

  /** Drives the response interceptor with a 401, as a request sent under `epoch` would arrive. */
  function reject401(config: Record<string, unknown> = {}) {
    const handler = (client.api.interceptors.response as unknown as {
      handlers: { rejected: (e: unknown) => Promise<unknown> }[];
    }).handlers[0].rejected;
    return handler({
      config: { url: '/accounts', headers: {}, ...config },
      response: { status: 401, data: { message: 'expired' } },
    });
  }

  /** The pair the last sign-out was handed by a refresh still in flight. */
  let late: Promise<import('./client').RefreshedPair | null>;

  /** What sign-out does to the client and to storage, without the React tree around it. */
  function signOut() {
    late = client.markSessionEnded();
    secureStore.__store.delete(TOKEN_KEY);
    secureStore.__store.delete(REFRESH_TOKEN_KEY);
    client.sessionStorageCleared();
  }

  it('THE INCIDENT: a refresh that finishes after sign-out writes nothing back', async () => {
    secureStore.__store.set(TOKEN_KEY, 'access-R1');
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
    refreshGate = deferred();

    const background = reject401().catch((e) => e);
    await until(() => presented.length === 1, 'the refresh to reach the server');

    signOut();
    refreshGate.release();
    await background;

    expect(secureStore.__store.has(TOKEN_KEY)).toBe(false);
    expect(secureStore.__store.has(REFRESH_TOKEN_KEY)).toBe(false);
    // The pair the server issued is handed to the sign-out's clean-up, not ended here: ending it
    // here would race that clean-up (see markSessionEnded).
    expect(await late).toEqual({ token: 'access-R2', refreshToken: 'R2' });
    expect(loggedOut).toEqual([]);
    // Not a session expiry: nothing to report and nothing more to clear.
    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(reportHandledEvent).not.toHaveBeenCalled();
  });

  it('takes back the pair when sign-out lands while it is being written', async () => {
    secureStore.__store.set(TOKEN_KEY, 'access-R1');
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
    const real = secureStore.setItemAsync.getMockImplementation()!;
    secureStore.setItemAsync.mockImplementation(async (key: string, value: string) => {
      await real(key, value);
      // Sign-out arrives between the access-token write and the refresh-token write.
      if (key === TOKEN_KEY) signOut();
    });

    await reject401().catch(() => {});

    expect(secureStore.__store.has(TOKEN_KEY)).toBe(false);
    expect(secureStore.__store.has(REFRESH_TOKEN_KEY)).toBe(false);
    expect(await late).toEqual({ token: 'access-R2', refreshToken: 'R2' });
  });

  it('leaves a session signed in after the sign-out untouched', async () => {
    secureStore.__store.set(TOKEN_KEY, 'access-R1');
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
    refreshGate = deferred();

    const background = reject401().catch(() => {});
    await until(() => presented.length === 1, 'the refresh to reach the server');

    signOut();
    // Someone signs straight back in before the old refresh returns.
    secureStore.__store.set(TOKEN_KEY, 'access-NEW');
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'NEW');

    refreshGate.release();
    await background;
    expect(await late).toEqual({ token: 'access-R2', refreshToken: 'R2' });

    expect(secureStore.__store.get(TOKEN_KEY)).toBe('access-NEW');
    expect(secureStore.__store.get(REFRESH_TOKEN_KEY)).toBe('NEW');
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('a 401 for a request sent before sign-out starts no refresh', async () => {
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
    const epochAtSend = 0;

    signOut();
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'NEXT-SESSION');
    await expect(reject401({ _sessionEpoch: epochAtSend })).rejects.toBeTruthy();

    expect(presented).toEqual([]);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('a request carrying its own credentials never refreshes on a 401', async () => {
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');

    await expect(reject401({ _skipAuthRefresh: true })).rejects.toBeTruthy();

    expect(presented).toEqual([]);
  });

  it('the next session refreshes on its own instead of joining the discarded refresh', async () => {
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
    refreshGate = deferred();
    const old = reject401().catch(() => {});
    await until(() => presented.length === 1, 'the old refresh to reach the server');

    signOut();
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'NEW');
    const fresh = reject401().catch(() => {});
    await until(() => presented.length === 2, "the new session's own refresh");

    refreshGate.release();
    await Promise.all([old, fresh]);

    expect(presented).toEqual(['R1', 'NEW']);
    expect(secureStore.__store.get(REFRESH_TOKEN_KEY)).toBe('R3');
  });

  it("a discarded refresh finishing does not reopen the guard on the next session's refresh", async () => {
    // If the old refresh's cleanup cleared the guard unconditionally, a third 401 would start a
    // second refresh with the new session's token while its first was still out -- the same token
    // presented twice, which the backend treats as theft and answers by ending every session.
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
    gates.set('R1', deferred());
    gates.set('NEW', deferred());
    const old = reject401().catch(() => {});
    await until(() => presented.length === 1, 'the old refresh to reach the server');

    signOut();
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'NEW');
    const first = reject401().catch(() => {});
    await until(() => presented.length === 2, "the new session's refresh");

    gates.get('R1')!.release();
    await old;
    const second = reject401().catch(() => {});
    await new Promise((r) => setTimeout(r, 20));

    expect(presented).toEqual(['R1', 'NEW']);
    gates.get('NEW')!.release();
    await Promise.all([first, second]);
  });

  it('takes back only what it wrote when a new session writes in between', async () => {
    secureStore.__store.set(TOKEN_KEY, 'access-R1');
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
    const real = secureStore.setItemAsync.getMockImplementation()!;
    secureStore.setItemAsync.mockImplementation(async (key: string, value: string) => {
      await real(key, value);
      if (key === TOKEN_KEY && value === 'access-R2') {
        signOut();
        secureStore.__store.set(TOKEN_KEY, 'access-NEW'); // the next session's own write
      }
    });

    await reject401().catch(() => {});
    expect(await late).toEqual({ token: 'access-R2', refreshToken: 'R2' });

    expect(secureStore.__store.get(TOKEN_KEY)).toBe('access-NEW');
    expect(secureStore.__store.has(REFRESH_TOKEN_KEY)).toBe(false);
  });

  it('no refresh can start between sign-out and its storage being cleared', async () => {
    // The ending session's tokens are still readable for that moment; a refresh started then would
    // rotate the very session being ended, behind the sign-out's back.
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');

    late = client.markSessionEnded();
    await expect(reject401()).rejects.toBeTruthy();
    expect(presented).toEqual([]);
    expect(onSessionExpired).not.toHaveBeenCalled();

    client.sessionStorageCleared();
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'NEXT');
    await reject401().catch(() => {});
    expect(presented).toEqual(['NEXT']);
  });

  it('with no refresh in flight, sign-out is handed nothing', async () => {
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
    signOut();
    expect(await late).toBeNull();
  });

  it('a session expiry (failed refresh) releases the block for the next session', async () => {
    // clearSessionAndRedirect ends the session too; if it left refreshes blocked, the next person to
    // sign in on this device could never refresh and would be thrown out every fifteen minutes.
    client.rawApi.defaults.adapter = (async (config: { url?: string; data?: string }) => {
      const { refreshToken } = JSON.parse(config.data ?? '{}');
      presented.push(refreshToken);
      if (refreshToken === 'DEAD') {
        const err: Error & { response?: unknown } = new Error('expired');
        err.response = { status: 401, data: { errorCode: 'AUTH_TOKEN_EXPIRED' } };
        throw err;
      }
      return { data: { data: { token: 'access-OK', refreshToken: 'OK2' } }, status: 200, statusText: 'OK', headers: {}, config };
    }) as never;
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'DEAD');
    await reject401().catch(() => {});
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
    // AUTH_TOKEN_EXPIRED is time running out, not a deliberate sign-out: notifications stay.
    expect(onSessionExpired).toHaveBeenCalledWith({ endedDeliberately: false });

    secureStore.__store.set(REFRESH_TOKEN_KEY, 'OK1');
    await reject401().catch(() => {});
    expect(presented).toEqual(['DEAD', 'OK1']);
    expect(secureStore.__store.get(REFRESH_TOKEN_KEY)).toBe('OK2');
  });

  describe('why a rejected refresh ended the session', () => {
    function serverRejectsWith(errorCode: string | null) {
      client.rawApi.defaults.adapter = (async () => {
        const err: Error & { response?: unknown } = new Error('rejected');
        err.response = errorCode === null ? undefined : { status: 401, data: { errorCode } };
        throw err;
      }) as never;
    }

    it.each<[string | null, boolean, string]>([
      ['AUTH_004', true, 'revoked: signed out from another device, or a security sign-out'],
      ['AUTH_007', true, 'account deactivated'],
      ['AUTH_005', false, 'idle timeout'],
      ['AUTH_006', false, 'session max age'],
      ['AUTH_002', false, 'unknown or expired token'],
      [null, false, 'no response at all (offline)'],
    ])('%s -> endedDeliberately %s (%s)', async (errorCode, deliberate) => {
      secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
      serverRejectsWith(errorCode);

      await reject401().catch(() => {});

      expect(onSessionExpired).toHaveBeenCalledWith({ endedDeliberately: deliberate });
    });
  });

  it('CONTROL: with no sign-out, the refreshed pair is persisted as before', async () => {
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');

    await reject401().catch(() => {});

    expect(secureStore.__store.get(TOKEN_KEY)).toBe('access-R2');
    expect(secureStore.__store.get(REFRESH_TOKEN_KEY)).toBe('R2');
    expect(loggedOut).toEqual([]);
  });

  it('every request records the session it was sent under', async () => {
    const seen: unknown[] = [];
    client.api.defaults.adapter = (async (config: { _sessionEpoch?: number }) => {
      seen.push(config._sessionEpoch);
      return { data: { success: true, data: {} }, status: 200, statusText: 'OK', headers: {}, config };
    }) as never;

    await client.api.get('/accounts');
    client.markSessionEnded();
    await client.api.get('/accounts');

    expect(seen).toEqual([0, 1]);
  });

  it('an Authorization the caller set is not replaced by the stored token', async () => {
    secureStore.__store.set(TOKEN_KEY, 'stored-access');
    let sent: string | undefined;
    client.api.defaults.adapter = (async (config: { headers: Record<string, string> }) => {
      sent = config.headers.Authorization;
      return { data: { success: true, data: {} }, status: 200, statusText: 'OK', headers: {}, config };
    }) as never;

    await client.api.get('/accounts', { headers: { Authorization: 'Bearer departing-access' } });

    expect(sent).toBe('Bearer departing-access');
  });
});
