/**
 * Privacy workstream 10 on the API client: the suspended screen's own calls, and a signed-in
 * session that starts being refused because the account was suspended.
 *
 * Real interceptors, real storage wrapper and real endpoints, with a fake server underneath (the
 * same harness as refreshRace.test.ts and signOutDuringRefresh.test.ts).
 */
import { AxiosError } from 'axios';

process.env.EXPO_PUBLIC_API_BASE_URL = 'https://tests.invalid';

jest.mock('../lib/monitoring', () => ({
  reportHandledEvent: jest.fn(),
  reportHandledError: jest.fn(),
}));

jest.mock('expo-file-system', () => ({
  File: jest.fn().mockImplementation(() => ({
    exists: false,
    delete: jest.fn(),
    create: jest.fn(),
    writeSync: jest.fn(),
    uri: 'file:///mock/export.zip',
  })),
  Paths: { cache: 'mock-cache-dir' },
}));

jest.mock('expo-sharing', () => ({
  isAvailableAsync: jest.fn().mockResolvedValue(true),
  shareAsync: jest.fn().mockResolvedValue(undefined),
}));

const TOKEN_KEY = 'finora_token';
const REFRESH_TOKEN_KEY = 'finora_refresh_token';
const SUSPENDED_TOKEN = 'suspended-screen-token';
const SIGN_OUT_MESSAGE = 'This account has been suspended. Sign in to see why and what you can do.';

type Sent = { url: string; method: string; authorization: string | undefined; data: unknown };
type Reply = { status: number; data: unknown };

describe('suspended account on the API client', () => {
  let client: typeof import('./client');
  let endpoints: typeof import('./endpoints');
  let secureStore: { __store: Map<string, string>; getItemAsync: jest.Mock };
  let onSessionExpired: jest.Mock;
  let sent: Sent[];
  /** What the fake server answers, by URL. Anything not listed gets an empty 200. */
  let replies: Map<string, Reply>;

  function envelope403(errorCode: string, details?: Record<string, unknown>) {
    return { success: false, message: 'Your account has been suspended.', errorCode, details: details ?? null };
  }

  beforeEach(() => {
    jest.resetModules();
    client = require('./client');
    endpoints = require('./endpoints');
    secureStore = require('expo-secure-store');
    secureStore.__store.clear();
    secureStore.getItemAsync.mockClear();
    onSessionExpired = jest.fn();
    client.setSessionCallbacks({ onSessionExpired });
    sent = [];
    replies = new Map();

    const adapter = async (config: { url?: string; method?: string; headers: Record<string, string>; data?: string }) => {
      const url = config.url ?? '';
      sent.push({
        url,
        method: config.method ?? 'get',
        authorization: config.headers.Authorization,
        data: config.data ? JSON.parse(config.data) : undefined,
      });
      const reply = replies.get(url) ?? { status: 200, data: { success: true, data: {} } };
      const response = { data: reply.data, status: reply.status, statusText: '', headers: {}, config };
      if (reply.status >= 400) {
        throw new AxiosError(`Request failed with status code ${reply.status}`, 'ERR_BAD_REQUEST', config as never, null, response as never);
      }
      return response;
    };
    client.api.defaults.adapter = adapter as never;
    client.rawApi.defaults.adapter = adapter as never;
  });

  describe("the suspended screen's calls", () => {
    it('send the suspended token, not a stale session left in storage', async () => {
      secureStore.__store.set(TOKEN_KEY, 'stale-session-token');
      replies.set('/users/me/suspension', {
        status: 200,
        data: { success: true, data: { reasonText: 'Fraud', requests: [] } },
      });

      const status = await endpoints.suspensionApi.status(SUSPENDED_TOKEN);

      expect(status).toEqual({ reasonText: 'Fraud', requests: [] });
      expect(sent).toEqual([
        expect.objectContaining({ url: '/users/me/suspension', authorization: `Bearer ${SUSPENDED_TOKEN}` }),
      ]);
    });

    it('send an appeal with its text, and a deletion request with no text', async () => {
      await endpoints.suspensionApi.raiseRequest(SUSPENDED_TOKEN, 'APPEAL', 'This was a mistake.');
      await endpoints.suspensionApi.raiseRequest(SUSPENDED_TOKEN, 'DELETION');

      expect(sent).toEqual([
        { url: '/users/me/suspension/requests', method: 'post', authorization: `Bearer ${SUSPENDED_TOKEN}`,
          data: { type: 'APPEAL', text: 'This was a mistake.' } },
        { url: '/users/me/suspension/requests', method: 'post', authorization: `Bearer ${SUSPENDED_TOKEN}`,
          data: { type: 'DELETION' } },
      ]);
    });

    it('download the data export under the suspended token', async () => {
      replies.set('/users/me/data-export', { status: 200, data: new ArrayBuffer(4) });

      await endpoints.suspensionApi.exportData(SUSPENDED_TOKEN, 'pw', null, null);

      expect(sent).toEqual([
        { url: '/users/me/data-export', method: 'post', authorization: `Bearer ${SUSPENDED_TOKEN}`,
          data: { currentPassword: 'pw', googleIdToken: null, appleIdToken: null } },
      ]);
      expect(require('expo-sharing').shareAsync).toHaveBeenCalled();
    });

    it('on a 401 (the token expired) neither refresh nor sign out, even with a session in storage', async () => {
      // The dangerous state: a stale refresh token on the device. Presenting it could revoke every
      // session the user has; signing out would navigate away from an appeal being typed.
      secureStore.__store.set(TOKEN_KEY, 'stale-session-token');
      secureStore.__store.set(REFRESH_TOKEN_KEY, 'stale-refresh-token');
      replies.set('/users/me/suspension/requests', { status: 401, data: { success: false, message: 'Unauthorized' } });

      const failure = endpoints.suspensionApi.raiseRequest(SUSPENDED_TOKEN, 'APPEAL', 'text');

      await expect(failure).rejects.toMatchObject({ response: { status: 401 } });
      expect(secureStore.getItemAsync.mock.calls.some(([key]) => key === REFRESH_TOKEN_KEY)).toBe(false);
      expect(onSessionExpired).not.toHaveBeenCalled();
      expect(secureStore.__store.get(TOKEN_KEY)).toBe('stale-session-token');
    });

    it('on a 401 from the data export neither refresh nor sign out', async () => {
      secureStore.__store.set(REFRESH_TOKEN_KEY, 'stale-refresh-token');
      const body = Uint8Array.from(Buffer.from(JSON.stringify({ success: false, message: 'Unauthorized' }), 'utf8'));
      replies.set('/users/me/data-export', { status: 401, data: body.buffer });

      await expect(endpoints.suspensionApi.exportData(SUSPENDED_TOKEN, 'pw', null, null))
        .rejects.toMatchObject({ response: { status: 401 } });
      expect(secureStore.getItemAsync.mock.calls.some(([key]) => key === REFRESH_TOKEN_KEY)).toBe(false);
      expect(onSessionExpired).not.toHaveBeenCalled();
    });

    it('on an AUTH_018 of their own do not end anything', async () => {
      secureStore.__store.set(TOKEN_KEY, 'other-session-token');
      replies.set('/users/me/suspension', { status: 403, data: envelope403('AUTH_018') });

      await expect(endpoints.suspensionApi.status(SUSPENDED_TOKEN)).rejects.toBeDefined();
      expect(onSessionExpired).not.toHaveBeenCalled();
      expect(secureStore.__store.get(TOKEN_KEY)).toBe('other-session-token');
    });
  });

  describe('a sign-in refused because the account is suspended', () => {
    it('keeps the token in the error for the sign-in screen, and signs nothing out', async () => {
      replies.set('/auth/login', {
        status: 403,
        data: envelope403('AUTH_018', { suspendedAccessToken: SUSPENDED_TOKEN }),
      });
      const { suspendedTokenFrom } = require('../lib/suspension');

      const err = await endpoints.authApi.login('someone@example.com', 'pw').catch((e: unknown) => e);

      expect(suspendedTokenFrom(err)).toBe(SUSPENDED_TOKEN);
      expect(onSessionExpired).not.toHaveBeenCalled();
    });
  });

  describe('a signed-in session whose account is suspended', () => {
    it('signs out with the reason on the first AUTH_018', async () => {
      secureStore.__store.set(TOKEN_KEY, 'session-token');
      secureStore.__store.set(REFRESH_TOKEN_KEY, 'session-refresh');
      replies.set('/accounts', { status: 403, data: envelope403('AUTH_018') });

      await expect(client.api.get('/accounts')).rejects.toMatchObject({ response: { status: 403 } });

      expect(sent[0].authorization).toBe('Bearer session-token');
      expect(onSessionExpired).toHaveBeenCalledTimes(1);
      expect(onSessionExpired).toHaveBeenCalledWith({ endedDeliberately: false, message: SIGN_OUT_MESSAGE });
      expect(secureStore.__store.has(TOKEN_KEY)).toBe(false);
      expect(secureStore.__store.has(REFRESH_TOKEN_KEY)).toBe(false);
    });

    it('signs out once for a burst of refused requests', async () => {
      secureStore.__store.set(TOKEN_KEY, 'session-token');
      replies.set('/accounts', { status: 403, data: envelope403('AUTH_018') });
      replies.set('/budgets', { status: 403, data: envelope403('AUTH_018') });
      replies.set('/goals', { status: 403, data: envelope403('AUTH_018') });

      await Promise.allSettled([client.api.get('/accounts'), client.api.get('/budgets'), client.api.get('/goals')]);

      expect(onSessionExpired).toHaveBeenCalledTimes(1);
    });

    it('leaves other 403s alone', async () => {
      secureStore.__store.set(TOKEN_KEY, 'session-token');
      replies.set('/accounts', { status: 403, data: envelope403('ENTITLEMENT_002') });

      await expect(client.api.get('/accounts')).rejects.toBeDefined();

      expect(onSessionExpired).not.toHaveBeenCalled();
      expect(secureStore.__store.get(TOKEN_KEY)).toBe('session-token');
    });

    it('does nothing for a request sent with no session at all', async () => {
      replies.set('/accounts', { status: 403, data: envelope403('AUTH_018') });

      await expect(client.api.get('/accounts')).rejects.toBeDefined();

      expect(onSessionExpired).not.toHaveBeenCalled();
    });
  });
});
