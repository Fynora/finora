import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import { IdleSignOut } from './IdleSignOut';
import { AdminAuthProvider } from '../context/AdminAuthContext';
import { ADMIN_SESSION_ENDED_REASON_KEY, api, rawApi } from '../api/client';
import { LAST_ACTIVITY_KEY, __resetIdleClockForTests } from '../lib/idleSignOut';

const IDLE_REASON = 'You were signed out after 15 minutes of inactivity.';

/**
 * A page load of an admin session that went idle hours ago, through the real AdminAuthProvider and
 * request client; only the network is faked. The fake server behaves like the real one on the point
 * that matters (AccessTokenSessionRevocationIT.logoutEndsTheAccessTokenAndNotJustTheRefreshToken):
 * once /auth/logout arrives, the session's access token is refused with 401.
 *
 * The portal sets the token before its /users/me/access check answers. Signing out in that gap made
 * the check come back 401 with no token left in the client, which sends the admin through
 * endSessionAndRedirect -- a full page reload that replaces the inactivity reason with a generic
 * "session has ended". IdleSignOut waits for the check instead.
 */
describe('IdleSignOut on a page load (admin portal)', () => {
  const calls: string[] = [];
  let loggedOut = false;
  const realApiAdapter = api.defaults.adapter;
  const realRawAdapter = rawApi.defaults.adapter;

  function respond(config: InternalAxiosRequestConfig, status: number, data: unknown) {
    if (status >= 400) {
      return Promise.reject(Object.assign(new Error(`HTTP ${status}`), {
        config, isAxiosError: true, response: { status, data, config, headers: {}, statusText: '' },
      }));
    }
    return Promise.resolve({ config, status, statusText: 'OK', headers: {}, data });
  }

  const server: AxiosAdapter = async (config) => {
    const url = config.url ?? '';
    calls.push(url);
    if (url.endsWith('/auth/refresh')) {
      return respond(config, 200, { success: true, data: { token: 'admin-access', refreshToken: 'r' } });
    }
    if (url.endsWith('/users/me/access')) {
      // The permission check takes a moment; a sign-out landing meanwhile ends its token.
      await new Promise((resolve) => setTimeout(resolve, 30));
      if (loggedOut) return respond(config, 401, { success: false, message: 'Session ended' });
      return respond(config, 200, { success: true, data: { permissions: ['AUDIT_VIEW'], roles: ['ADMIN'] } });
    }
    if (url.endsWith('/auth/logout')) {
      loggedOut = true;
      return respond(config, 200, { success: true, data: null });
    }
    return respond(config, 404, { success: false });
  };

  beforeEach(() => {
    calls.length = 0;
    loggedOut = false;
    localStorage.clear();
    __resetIdleClockForTests();
    localStorage.setItem('finora_admin_phone_verified', 'true');
    localStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now() - 3 * 60 * 60_000));
    api.defaults.adapter = server;
    rawApi.defaults.adapter = server;
  });

  afterEach(() => {
    api.defaults.adapter = realApiAdapter;
    rawApi.defaults.adapter = realRawAdapter;
  });

  it('signs out after the permission check, keeping the inactivity reason for the sign-in page', async () => {
    render(
      <AdminAuthProvider>
        <IdleSignOut>
          <p>admin content</p>
        </IdleSignOut>
      </AdminAuthProvider>
    );

    await waitFor(() => expect(calls).toContain('/auth/logout'));
    // Let the check, and anything it set off, finish.
    await new Promise((resolve) => setTimeout(resolve, 60));

    expect(calls.indexOf('/users/me/access')).toBeLessThan(calls.indexOf('/auth/logout'));
    expect(localStorage.getItem(ADMIN_SESSION_ENDED_REASON_KEY)).toBe(IDLE_REASON);
  });
});
