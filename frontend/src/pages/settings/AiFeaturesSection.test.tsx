import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiFeaturesSection } from './AiFeaturesSection';
import { userApi, type UserSettings } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({ userApi: { get: vi.fn(), consent: vi.fn() } }));
vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ email: 'ai-switch@example.com' }) }));

function profile(aiProcessingConsent: boolean | null): UserSettings {
  return {
    email: 'ai-switch@example.com', fullName: 'AI Switch', lowBalanceThreshold: 0, theme: 'system',
    timezone: 'Asia/Kolkata', phoneNumber: null, phoneVerified: true, createdAt: '2026-10-01T00:00:00Z',
    passwordChangedAt: null, signInMethod: 'PASSWORD', onboardingCompleted: true,
    termsAcceptanceRequired: false, currentTermsVersion: '2026-10', aiProcessingConsent,
  };
}

function renderSection() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><AiFeaturesSection /></QueryClientProvider>);
}

/** The switch, once the setting has loaded (it is disabled until then). */
async function loadedSwitch() {
  const toggle = screen.getByRole('switch', { name: 'AI features' });
  await waitFor(() => expect(toggle).toBeEnabled());
  return toggle;
}

beforeEach(() => {
  vi.mocked(userApi.get).mockReset();
  vi.mocked(userApi.consent).mockReset();
});

describe('AiFeaturesSection', () => {
  it('shows the switch off for a no, and turns it on without asking', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile(false));
    vi.mocked(userApi.consent).mockResolvedValue(profile(true));
    renderSection();

    const toggle = await loadedSwitch();
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    fireEvent.click(toggle);

    expect(await screen.findByRole('switch', { checked: true })).toBeInTheDocument();
    expect(userApi.consent).toHaveBeenCalledWith({ aiProcessing: true });
  });

  it('shows never-asked as off', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile(null));
    renderSection();

    expect(await loadedSwitch()).toHaveAttribute('aria-checked', 'false');
  });

  /** Turning off is withdrawal: the stored AI texts are deleted, so it asks first. */
  it('asks before turning off, and does nothing on cancel', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile(true));
    renderSection();

    fireEvent.click(await loadedSwitch());
    expect(screen.getByText('Turn off AI features?')).toBeInTheDocument();
    expect(screen.getByText(/AI insight texts written for you so far are deleted/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(userApi.consent).not.toHaveBeenCalled();
    expect(screen.getByRole('switch', { checked: true })).toBeInTheDocument();
  });

  it('turns off once confirmed', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile(true));
    vi.mocked(userApi.consent).mockResolvedValue(profile(false));
    renderSection();

    fireEvent.click(await loadedSwitch());
    fireEvent.click(screen.getByRole('button', { name: 'Turn off' }));

    expect(await screen.findByRole('switch', { checked: false })).toBeInTheDocument();
    expect(userApi.consent).toHaveBeenCalledWith({ aiProcessing: false });
  });

  it('says so when saving fails, and keeps the old state', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile(false));
    vi.mocked(userApi.consent).mockRejectedValue(new Error('network down'));
    renderSection();

    fireEvent.click(await loadedSwitch());

    expect(await screen.findByRole('alert')).toHaveTextContent(/Couldn't save that/);
    expect(screen.getByRole('switch', { checked: false })).toBeInTheDocument();
  });

  it('disables the switch when the setting cannot be loaded', async () => {
    vi.mocked(userApi.get).mockRejectedValue(new Error('network down'));
    renderSection();

    expect(await screen.findByText(/Couldn't load this setting/)).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'AI features' })).toBeDisabled();
  });
});
