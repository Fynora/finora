import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { AdminAuthProvider, useAdminAuth, AdminAccessError } from './AdminAuthContext';
import { authApi, meApi } from '../api/endpoints';
import { getAdminToken, setAdminToken } from '../api/client';

vi.mock('../api/endpoints', () => ({
  authApi: { login: vi.fn(), refresh: vi.fn(), verifyMfa: vi.fn(), logout: vi.fn().mockResolvedValue(undefined) },
  meApi: { access: vi.fn() },
}));

function wrapper({ children }: { children: ReactNode }) {
  return <AdminAuthProvider>{children}</AdminAuthProvider>;
}

// CASA 3.3.1: an admin who has not enrolled in two-factor authentication gets 403
// MFA_ENROLLMENT_REQUIRED from every endpoint but the enrolment ones, including the
// /users/me/access call that follows login. That is NOT a failed session -- the token is valid and
// the enrolment endpoints need it -- so it must never clear the session the way any other
// loadAccess failure does, on any of the three paths that call loadAccess().
const MFA_REQUIRED_ERROR = {
  response: { data: { message: 'Set up two-factor authentication to continue.', errorCode: 'MFA_ENROLLMENT_REQUIRED' } },
};

const LOGIN_OK = {
  token: 'tok', refreshToken: 'refresh', email: 'amy@example.com', fullName: 'Amy Admin', phoneVerified: true,
};

describe('AdminAuthContext: two-factor enrolment required', () => {
  beforeEach(() => {
    localStorage.clear();
    setAdminToken(null);
    vi.mocked(authApi.login).mockReset();
    vi.mocked(meApi.access).mockReset();
    vi.mocked(authApi.refresh).mockReset().mockRejectedValue(new Error('no session'));
  });

  it('login keeps the session and flags enrolment as required instead of failing', async () => {
    vi.mocked(authApi.login).mockResolvedValue(LOGIN_OK);
    vi.mocked(meApi.access).mockRejectedValue(MFA_REQUIRED_ERROR);
    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    const resolvedTo = await act(() => result.current.login('amy@example.com', 'password'));

    expect(resolvedTo).toBe(true);
    expect(result.current.mfaEnrollmentRequired).toBe(true);
    expect(result.current.token).toBe('tok');
    expect(getAdminToken()).toBe('tok');
    expect(result.current.email).toBe('amy@example.com');
  });

  it('a page reload keeps the recovered session and flags enrolment as required', async () => {
    vi.mocked(authApi.refresh).mockReset().mockResolvedValue({ token: 'recovered-tok', refreshToken: 'refresh' });
    vi.mocked(meApi.access).mockRejectedValue(MFA_REQUIRED_ERROR);

    const { result } = renderHook(() => useAdminAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.mfaEnrollmentRequired).toBe(true);
    expect(result.current.token).toBe('recovered-tok');
    expect(getAdminToken()).toBe('recovered-tok');
  });

  it('finishing phone verification does not drop the session when enrolment is the next step', async () => {
    vi.mocked(authApi.login).mockResolvedValue({ ...LOGIN_OK, phoneVerified: false });
    vi.mocked(meApi.access).mockRejectedValue(MFA_REQUIRED_ERROR);
    const { result } = renderHook(() => useAdminAuth(), { wrapper });
    await act(() => result.current.login('amy@example.com', 'password'));

    await act(() => result.current.completePhoneVerification());

    expect(result.current.phoneVerified).toBe(true);
    expect(result.current.mfaEnrollmentRequired).toBe(true);
    expect(result.current.token).toBe('tok');
    expect(result.current.email).toBe('amy@example.com');
  });

  it('completeMfaEnrollment clears the flag and loads the permissions that login deferred', async () => {
    vi.mocked(authApi.login).mockResolvedValue(LOGIN_OK);
    vi.mocked(meApi.access).mockRejectedValueOnce(MFA_REQUIRED_ERROR)
      .mockResolvedValueOnce({ roles: ['SUPER_ADMIN'], permissions: ['USER_VIEW'] });
    const { result } = renderHook(() => useAdminAuth(), { wrapper });
    await act(() => result.current.login('amy@example.com', 'password'));
    expect(result.current.mfaEnrollmentRequired).toBe(true);

    await act(() => result.current.completeMfaEnrollment());

    expect(result.current.mfaEnrollmentRequired).toBe(false);
    expect(result.current.permissions).toEqual(['USER_VIEW']);
    expect(result.current.token).toBe('tok');
  });

  it('completeMfaEnrollment leaves the gate up and keeps the session if the server still says enrol', async () => {
    vi.mocked(authApi.login).mockResolvedValue(LOGIN_OK);
    vi.mocked(meApi.access).mockRejectedValue(MFA_REQUIRED_ERROR);
    const { result } = renderHook(() => useAdminAuth(), { wrapper });
    await act(() => result.current.login('amy@example.com', 'password'));

    await expect(act(() => result.current.completeMfaEnrollment())).rejects.toBeInstanceOf(AdminAccessError);

    expect(result.current.mfaEnrollmentRequired).toBe(true);
    expect(result.current.token).toBe('tok');
  });

  it('logout clears the flag', async () => {
    vi.mocked(authApi.login).mockResolvedValue(LOGIN_OK);
    vi.mocked(meApi.access).mockRejectedValue(MFA_REQUIRED_ERROR);
    const { result } = renderHook(() => useAdminAuth(), { wrapper });
    await act(() => result.current.login('amy@example.com', 'password'));

    act(() => result.current.logout());

    expect(result.current.mfaEnrollmentRequired).toBe(false);
    expect(result.current.token).toBeNull();
  });
});
