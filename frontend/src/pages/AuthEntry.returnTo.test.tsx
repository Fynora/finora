import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useEffect } from 'react';
import AuthEntry from './AuthEntry';
import { AuthProvider } from '../context/AuthContext';
import { ProtectedRoute } from '../components/ProtectedRoute';
import { authApi } from '../api/endpoints';

/**
 * Return-to-after-login, end to end through the real ProtectedRoute, AuthProvider and AuthEntry:
 * a signed-out user opening an emailed /app deep link must land back on it after signing in.
 */

vi.mock('../api/endpoints', () => ({
  authApi: { identify: vi.fn(), login: vi.fn(), register: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

// No refresh cookie in a test: the silent bootstrap refresh fails, so the session starts signed out
// -- the same state a logged-out user opening an emailed link is in.
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  refreshAccessToken: vi.fn().mockRejectedValue(new Error('no refresh cookie')),
}));

// ProtectedRoute only reads the onboarding step; the logged-in user below has finished onboarding.
vi.mock('../onboarding/OnboardingUIContext', () => ({
  useOnboardingUI: () => ({ step: 'welcome', setStep: vi.fn() }),
}));

function WhereAmI() {
  const location = useLocation();
  return <div data-testid="landed">{`${location.pathname}${location.search}`}</div>;
}

function VerifyPhoneProbe() {
  const location = useLocation();
  return <div data-testid="verify-phone-state">{JSON.stringify(location.state)}</div>;
}

// Puts arbitrary router state on /auth, standing in for a tampered history entry.
function NavigateWithState({ state }: { state: unknown }) {
  const navigate = useNavigate();
  useEffect(() => { void navigate('/auth', { state, replace: true }); }, [navigate, state]);
  return null;
}

function renderAt(initialPath: string, tamperedState?: unknown) {
  render(
    <MemoryRouter initialEntries={[tamperedState === undefined ? initialPath : '/start']}>
      <AuthProvider>
        <Routes>
          <Route path="/auth" element={<AuthEntry />} />
          <Route path="/verify-phone" element={<VerifyPhoneProbe />} />
          <Route path="/app" element={<ProtectedRoute><div data-testid="landed">/app</div></ProtectedRoute>} />
          <Route path="/app/*" element={<ProtectedRoute><WhereAmI /></ProtectedRoute>} />
          <Route path="/start" element={<NavigateWithState state={tamperedState} />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>
  );
}

async function signIn(phoneVerified = true) {
  vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
  vi.mocked(authApi.login).mockResolvedValue({
    data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified, onboardingCompleted: true },
  } as any);
  await userEvent.type(await screen.findByLabelText('Email or mobile number'), 'jane@example.com');
  await userEvent.click(screen.getByRole('button', { name: /continue/i }));
  await userEvent.type(await screen.findByLabelText('Password'), 'correct-password-1');
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
}

describe('AuthEntry return-to-after-login', () => {
  beforeEach(() => {
    vi.mocked(authApi.identify).mockReset();
    vi.mocked(authApi.login).mockReset();
    localStorage.clear();
  });

  it('a signed-out deep link to an import survives sign-in', async () => {
    renderAt('/app/imports/job-123');

    // ProtectedRoute bounced the signed-out user to /auth first.
    expect(await screen.findByLabelText('Email or mobile number')).toBeInTheDocument();
    await signIn();

    await waitFor(() => expect(screen.getByTestId('landed')).toHaveTextContent('/app/imports/job-123'));
  });

  it('keeps the query string (the notifications opt-out link)', async () => {
    renderAt('/app/settings?tab=notifications');
    await signIn();

    await waitFor(() => expect(screen.getByTestId('landed')).toHaveTextContent('/app/settings?tab=notifications'));
  });

  it('defaults to the dashboard when /auth was opened directly', async () => {
    renderAt('/auth');
    await signIn();

    await waitFor(() => expect(screen.getByTestId('landed')).toHaveTextContent(/^\/app$/));
  });

  it.each([
    ['an absolute URL', 'https://evil.example/app'],
    ['a protocol-relative URL', '//evil.example/app'],
    ['a non-/app path', '/terms'],
  ])('ignores %s in the return target and goes to the dashboard', async (_label, from) => {
    renderAt('/auth', { from });
    await signIn();

    await waitFor(() => expect(screen.getByTestId('landed')).toHaveTextContent(/^\/app$/));
  });

  it('carries the deep link into phone verification for an unverified account', async () => {
    renderAt('/app/imports/job-123');
    await signIn(false);

    await waitFor(() => expect(screen.getByTestId('verify-phone-state')).toHaveTextContent(
      JSON.stringify({ fromLogin: true, from: '/app/imports/job-123' }),
    ));
  });

  it('does not forward a rejected target into phone verification', async () => {
    renderAt('/auth', { from: '//evil.example' });
    await signIn(false);

    await waitFor(() => expect(screen.getByTestId('verify-phone-state')).toHaveTextContent(
      JSON.stringify({ fromLogin: true }),
    ));
  });
});
