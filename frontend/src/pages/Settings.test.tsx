import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Settings from './Settings';
import { ThemeProvider } from '../context/ThemeContext';
import { AuthProvider } from '../context/AuthContext';

vi.mock('../api/endpoints', () => ({
  userApi: { get: vi.fn().mockResolvedValue({
    phoneNumber: '', phoneVerified: false, passwordChangedAt: null, signInMethod: 'PASSWORD',
    lowBalanceThreshold: 2000, timezone: 'Asia/Kolkata',
  }) },
  workspaceApi: { getSettings: vi.fn().mockResolvedValue({ autoApplyConfidenceThreshold: 90 }) },
  analyticsApi: { importStatistics: vi.fn().mockResolvedValue(null) },
  deviceApi: { list: vi.fn().mockResolvedValue([]) },
  gmailApi: { status: vi.fn().mockResolvedValue({ available: false }) },
  entitlementsApi: { mine: vi.fn().mockResolvedValue({ planCode: 'FREE', planName: 'Free', features: {} }) },
  accountAggregatorApi: { list: vi.fn().mockResolvedValue([]) },
  onboardingApi: { reset: vi.fn() },
  authApi: { refresh: vi.fn().mockRejectedValue(new Error('no session')), logout: vi.fn() },
}));

function renderSettings(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AuthProvider>
        <ThemeProvider>
          <Settings />
        </ThemeProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('Settings shell', () => {
  it('defaults to the General pane and switches panes on nav click', async () => {
    renderSettings('/app/settings');
    expect(await screen.findByText('Low balance alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Security' }));
    expect(await screen.findByText('Active Sessions')).toBeInTheDocument();
  });

  it('reads the initial pane from the ?tab= query param', async () => {
    renderSettings('/app/settings?tab=data');
    expect(await screen.findByText('Statements Imported')).toBeInTheDocument();
  });
});
