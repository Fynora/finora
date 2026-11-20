import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Settings from './Settings';
import { ThemeProvider } from '../context/ThemeContext';
import { AuthProvider } from '../context/AuthContext';
import { gmailApi } from '../api/endpoints';

// Gmail sync switched on, which Settings.test.tsx cannot cover: there it is paused, as shipped.
vi.mock('../lib/features', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/features')>()),
  GMAIL_SYNC_UI_ENABLED: true,
}));

vi.mock('./settings/AiFeaturesSection', () => ({ AiFeaturesSection: () => null }));

vi.mock('../api/endpoints', () => ({
  userApi: { get: vi.fn().mockResolvedValue({
    phoneNumber: '', phoneVerified: false, passwordChangedAt: null, signInMethod: 'PASSWORD',
    lowBalanceThreshold: 2000, timezone: 'Asia/Kolkata',
  }) },
  workspaceApi: { getSettings: vi.fn().mockResolvedValue({ autoApplyConfidenceThreshold: 90 }) },
  gmailApi: {
    confirmConnect: vi.fn().mockResolvedValue({}),
    status: vi.fn().mockResolvedValue({
      available: true, connected: true, needsReconnect: false, googleEmail: 'amy@example.com',
      grantedScopes: [], connectedAt: null, lastSyncedAt: null, lastDiscoveryAt: null, transactionsFound: 0, needsReview: 0,
    }),
  },
  entitlementsApi: { mine: vi.fn().mockResolvedValue({ planCode: 'PREMIUM', planName: 'Premium', features: { GMAIL_SYNC: true } }) },
  authApi: { refresh: vi.fn().mockRejectedValue(new Error('no session')), logout: vi.fn() },
}));

// Google's redirect lands on /app/settings?gmail=confirm&ticket=... with no ?tab=. Settings opens
// General by default, and General never posts the ticket, so the mailbox would never be connected.
describe('Settings, back from Google', () => {
  it('opens Connected Apps, which posts the ticket, and stays there once the URL is cleaned', async () => {
    render(
      <MemoryRouter initialEntries={['/app/settings?gmail=confirm&ticket=the-ticket']}>
        <AuthProvider>
          <ThemeProvider>
            <Settings />
          </ThemeProvider>
        </AuthProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(gmailApi.confirmConnect).toHaveBeenCalledWith('the-ticket'));
    expect(await screen.findByText('Gmail connected.')).toBeInTheDocument();
    expect(await screen.findByText('amy@example.com')).toBeInTheDocument();
    expect(screen.queryByText('Low balance alert')).not.toBeInTheDocument();
    expect(gmailApi.confirmConnect).toHaveBeenCalledTimes(1);
  });
});
