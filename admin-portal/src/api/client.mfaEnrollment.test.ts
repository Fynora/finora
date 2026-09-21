import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { api, setAdminToken } from './client';

vi.mock('./endpoints', () => ({
  authApi: { refresh: vi.fn() },
}));

// Same approach as client.test.ts: no HTTP-mocking library here, so the response interceptor's
// rejection handler is invoked directly.
function rejectedHandler() {
  const handlers = (api.interceptors.response as any).handlers;
  return handlers[handlers.length - 1].rejected;
}

function forbidden(errorCode: string) {
  return {
    response: { status: 403, data: { message: 'refused', errorCode } },
    config: { url: '/admin/users', _retried: false, headers: {} },
  };
}

// CASA 3.3.1: AdminMfaEnrollmentFilter 403s MFA_ENROLLMENT_REQUIRED on every endpoint but the
// enrolment ones. Like PHONE_VERIFICATION_REQUIRED, the session is fine -- the account has one step
// left -- so the client sends the admin to the screen that finishes it instead of leaving pages
// stuck on an unexplained 403.
describe('api response interceptor: two-factor enrolment required', () => {
  const realLocation = window.location;

  function stubLocation(pathname: string) {
    Object.defineProperty(window, 'location', {
      configurable: true, writable: true, value: { pathname, href: `http://localhost${pathname}` },
    });
  }

  beforeEach(() => {
    setAdminToken('a-real-access-token');
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, writable: true, value: realLocation });
  });

  it('sends the admin to /setup-mfa', async () => {
    stubLocation('/users');

    await rejectedHandler()(forbidden('MFA_ENROLLMENT_REQUIRED')).catch(() => {});

    expect(window.location.href).toBe('/setup-mfa');
  });

  it('does not redirect again when already on the setup screen', async () => {
    stubLocation('/setup-mfa');

    await rejectedHandler()(forbidden('MFA_ENROLLMENT_REQUIRED')).catch(() => {});

    expect(window.location.href).toBe('http://localhost/setup-mfa');
  });

  it('leaves an ordinary 403 alone', async () => {
    stubLocation('/users');

    await rejectedHandler()(forbidden('AUTH_FORBIDDEN')).catch(() => {});

    expect(window.location.href).toBe('http://localhost/users');
  });

  it('still rejects, so the calling page sees the failure rather than a silent success', async () => {
    stubLocation('/users');

    await expect(rejectedHandler()(forbidden('MFA_ENROLLMENT_REQUIRED'))).rejects.toBeDefined();
  });
});
