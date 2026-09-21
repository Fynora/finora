import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import SetupMfa from './SetupMfa';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminMfaApi } from '../api/endpoints';
import { mockAdminAuthState } from '../test/mockAdminAuth';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  adminMfaApi: { status: vi.fn(), enroll: vi.fn(), confirm: vi.fn(), disable: vi.fn() },
  userApi: { get: vi.fn() },
}));

vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: vi.fn(), error: vi.fn() }),
}));

// The QR image is drawn by a real canvas library; jsdom has no canvas, and this page's behaviour
// does not depend on the picture, only on the manual-entry secret shown beside it.
vi.mock('qrcode', () => ({ default: { toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,AAAA') } }));

const RECOVERY_CODES = ['AAAAA-11111', 'BBBBB-22222', 'CCCCC-33333'];

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/setup-mfa']}>
        <Routes>
          <Route path="/setup-mfa" element={<SetupMfa />} />
          <Route path="/" element={<div>Dashboard</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('SetupMfa', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    vi.mocked(adminMfaApi.enroll).mockReset().mockResolvedValue({
      secret: 'JBSWY3DPEHPK3PXP', provisioningUri: 'otpauth://totp/example?secret=JBSWY3DPEHPK3PXP',
    });
    vi.mocked(adminMfaApi.confirm).mockReset().mockResolvedValue({ recoveryCodes: RECOVERY_CODES });
  });

  it('explains that two-factor authentication is required and offers to set it up', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ mfaEnrollmentRequired: true }));

    renderPage();

    expect(screen.getByRole('heading', { name: /set up two-factor authentication/i })).toBeInTheDocument();
    expect(screen.getByText(/required for every admin account/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /set up two-factor authentication/i })).toBeInTheDocument();
  });

  it('sends an admin who is already enrolled straight to the dashboard', () => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ mfaEnrollmentRequired: false }));

    renderPage();

    expect(screen.getByText('Dashboard')).toBeInTheDocument();
  });

  it('walks through enrolling, shows the recovery codes, and only then continues to the dashboard', async () => {
    const user = userEvent.setup();
    const completeMfaEnrollment = vi.fn().mockResolvedValue(undefined);
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ mfaEnrollmentRequired: true, completeMfaEnrollment }));
    renderPage();

    await user.click(screen.getByRole('button', { name: /set up two-factor authentication/i }));
    await user.type(await screen.findByLabelText(/code from your app/i), '123456');
    await user.click(screen.getByRole('button', { name: /confirm and turn on/i }));

    // The recovery codes are shown and the gate is NOT cleared yet: they are shown once, and are
    // the way back in if the authenticator is lost.
    for (const code of RECOVERY_CODES) {
      expect(await screen.findByText(code)).toBeInTheDocument();
    }
    expect(completeMfaEnrollment).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /^done$/i })).toBeDisabled();

    await user.click(screen.getByLabelText(/saved these recovery codes/i));
    await user.click(screen.getByRole('button', { name: /^done$/i }));

    await waitFor(() => expect(completeMfaEnrollment).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('Dashboard')).toBeInTheDocument();
  });

  it('shows the reason and stays put when finishing fails', async () => {
    const user = userEvent.setup();
    const completeMfaEnrollment = vi.fn().mockRejectedValue(new Error('Two-factor authentication must be set up before you can continue.'));
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ mfaEnrollmentRequired: true, completeMfaEnrollment }));
    renderPage();

    await user.click(screen.getByRole('button', { name: /set up two-factor authentication/i }));
    await user.type(await screen.findByLabelText(/code from your app/i), '123456');
    await user.click(screen.getByRole('button', { name: /confirm and turn on/i }));
    await user.click(await screen.findByLabelText(/saved these recovery codes/i));
    await user.click(screen.getByRole('button', { name: /^done$/i }));

    expect(await screen.findByText(/must be set up before you can continue/i)).toBeInTheDocument();
    expect(screen.queryByText('Dashboard')).not.toBeInTheDocument();
  });

  it('lets the admin sign out instead, so they are never trapped on this screen', async () => {
    const user = userEvent.setup();
    const logout = vi.fn();
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ mfaEnrollmentRequired: true, logout }));
    renderPage();

    await user.click(screen.getByRole('button', { name: /sign out/i }));

    expect(logout).toHaveBeenCalledTimes(1);
  });
});
