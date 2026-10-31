import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ConsentGate } from './ConsentGate';
import { userApi, type UserSettings } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  userApi: { get: vi.fn(), consent: vi.fn() },
  authApi: { logout: vi.fn() },
}));
// Its own file covers it; here it only has to open.
vi.mock('../DeleteAccountModal', () => ({
  DeleteAccountModal: () => <div data-testid="delete-account-modal" />,
}));
const { logout } = vi.hoisted(() => ({ logout: vi.fn() }));
vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ email: 'consent-gate@example.com', logout }) }));

function profile(overrides: Partial<UserSettings> = {}): UserSettings {
  return {
    email: 'consent-gate@example.com', fullName: 'Consent Gate', lowBalanceThreshold: 0, theme: 'system',
    timezone: 'Asia/Kolkata', phoneNumber: null, phoneVerified: true, createdAt: '2026-10-01T00:00:00Z',
    passwordChangedAt: null, signInMethod: 'GOOGLE', onboardingCompleted: true,
    termsAcceptanceRequired: false, currentTermsVersion: '2026-10', aiProcessingConsent: false,
    ...overrides,
  };
}

function renderGate() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <ConsentGate><p>The app</p></ConsentGate>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

beforeEach(() => {
  vi.mocked(userApi.get).mockReset();
  vi.mocked(userApi.consent).mockReset();
  logout.mockReset();
});

describe('ConsentGate', () => {
  it('shows the app once the Terms are accepted and the AI question answered', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile());
    renderGate();

    expect(await screen.findByText('The app')).toBeInTheDocument();
    expect(screen.queryByTestId('consent-notice')).not.toBeInTheDocument();
  });

  it('holds the app back while the profile loads', () => {
    vi.mocked(userApi.get).mockReturnValue(new Promise(() => {}));
    renderGate();

    expect(screen.queryByText('The app')).not.toBeInTheDocument();
  });

  /** A network failure never locks anyone out; AI stays off on the server regardless. */
  it('shows the app when the profile cannot be loaded', async () => {
    vi.mocked(userApi.get).mockRejectedValue(new Error('network down'));
    renderGate();

    expect(await screen.findByText('The app')).toBeInTheDocument();
  });

  it('asks an account with no acceptance for both, and cannot be passed without the required box', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile({ termsAcceptanceRequired: true, aiProcessingConsent: null }));
    renderGate();

    expect(await screen.findByTestId('consent-notice')).toBeInTheDocument();
    expect(screen.queryByText('The app')).not.toBeInTheDocument();
    expect(screen.getByLabelText(/I am 18 or older and agree/)).not.toBeChecked();
    expect(screen.getByLabelText(/Use AI features/)).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /skip/i })).not.toBeInTheDocument();
  });

  it('records the acceptance of the current version and the AI answer, then lets the user through', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile({ termsAcceptanceRequired: true, aiProcessingConsent: null }));
    vi.mocked(userApi.consent).mockResolvedValue(profile({ aiProcessingConsent: true }));
    renderGate();

    fireEvent.click(await screen.findByLabelText(/I am 18 or older and agree/));
    fireEvent.click(screen.getByLabelText(/Use AI features/));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('The app')).toBeInTheDocument();
    expect(userApi.consent).toHaveBeenCalledWith({ acceptTermsVersion: '2026-10', aiProcessing: true });
  });

  /** Accepted by the old notice, never asked about AI: the AI question only, and no is an answer. */
  it('asks only the AI question when the current Terms are already accepted', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile({ termsAcceptanceRequired: false, aiProcessingConsent: null }));
    vi.mocked(userApi.consent).mockResolvedValue(profile({ aiProcessingConsent: false }));
    renderGate();

    expect(await screen.findByText('One choice about AI features')).toBeInTheDocument();
    expect(screen.queryByLabelText(/I am 18 or older and agree/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('The app')).toBeInTheDocument();
    expect(userApi.consent).toHaveBeenCalledWith({ aiProcessing: false });
  });

  it('asks again when the Terms version moved on', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile({ termsAcceptanceRequired: true, aiProcessingConsent: true }));
    renderGate();

    expect(await screen.findByLabelText(/I am 18 or older and agree/)).toBeInTheDocument();
    // The earlier yes to AI is kept as the starting point.
    expect(screen.getByLabelText(/Use AI features/)).toBeChecked();
  });

  it('reloads and asks again when the documents changed while the step was open', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile({ termsAcceptanceRequired: true, aiProcessingConsent: null }));
    vi.mocked(userApi.consent).mockRejectedValue({ response: { status: 409, data: { errorCode: 'PRIVACY_002' } } });
    renderGate();

    fireEvent.click(await screen.findByLabelText(/I am 18 or older and agree/));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/changed just now/);
    await waitFor(() => expect(userApi.get).toHaveBeenCalledTimes(2));
    expect(screen.getByLabelText(/I am 18 or older and agree/)).not.toBeChecked();
    expect(screen.queryByText('The app')).not.toBeInTheDocument();
  });

  it('keeps the step up with a retry message when saving fails', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile({ aiProcessingConsent: null }));
    vi.mocked(userApi.consent).mockRejectedValue(new Error('network down'));
    renderGate();

    fireEvent.click(await screen.findByRole('button', { name: 'Continue' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Check your connection/);
    expect(screen.queryByText('The app')).not.toBeInTheDocument();
  });

  it('offers sign out and account deletion as the ways out', async () => {
    vi.mocked(userApi.get).mockResolvedValue(profile({ termsAcceptanceRequired: true, aiProcessingConsent: null }));
    renderGate();

    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(logout).toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Delete my account' }));
    expect(screen.getByTestId('delete-account-modal')).toBeInTheDocument();
  });
});
