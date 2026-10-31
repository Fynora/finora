import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { RegisterStep } from './RegisterStep';
import { AuthProvider } from '../../context/AuthContext';
import { authApi } from '../../api/endpoints';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../../lib/googleIdentity';

vi.mock('../../api/endpoints', () => ({
  authApi: { register: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

// Same wholesale mock IdentifyStep.test.tsx/GoogleSignInButton.test.tsx use -- lets this file test
// its own handleGoogleCredential wiring without re-testing GIS's own script-loading mechanics.
vi.mock('../../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));

afterEach(() => {
  vi.unstubAllEnvs();
});

function renderStep(props: Partial<Parameters<typeof RegisterStep>[0]> = {}) {
  const onSuccess = vi.fn();
  const onAccountExists = vi.fn();
  render(
    <MemoryRouter>
      <AuthProvider>
        <RegisterStep prefill={{}} referralCode={undefined} onSuccess={onSuccess} onAccountExists={onAccountExists} {...props} />
      </AuthProvider>
    </MemoryRouter>
  );
  return { onSuccess, onAccountExists };
}

async function fillValidForm() {
  await userEvent.type(screen.getByLabelText('Full name'), 'Jane Doe');
  await userEvent.type(screen.getByLabelText('Email'), 'jane@example.com');
  await userEvent.type(screen.getByLabelText('Mobile number'), '9876500011'); // synthetic-ok: fake sequential example number
  await userEvent.type(screen.getByLabelText('Password (min 8 characters)'), 'correct-password-1');
  await userEvent.type(screen.getByLabelText('Confirm password'), 'correct-password-1');
  await userEvent.click(screen.getByLabelText(/I am 18 or older and agree/));
}

describe('RegisterStep', () => {
  it('prefills the email field from the prefill prop', () => {
    renderStep({ prefill: { email: 'new@example.com' } });
    expect(screen.getByLabelText('Email')).toHaveValue('new@example.com');
  });

  // V302 (privacy workstream 8): the standalone notice and an explicit, unticked box. The Google
  // and Apple buttons cannot carry the box; ConsentGate asks those accounts right after sign-in.
  it('shows the notice and both boxes, neither ticked, with working document links', () => {
    renderStep();
    expect(screen.getByTestId('consent-notice')).toBeInTheDocument();
    expect(screen.getByLabelText(/I am 18 or older and agree/)).not.toBeChecked();
    expect(screen.getByLabelText(/Use AI features/)).not.toBeChecked();
    expect(screen.getByRole('link', { name: 'Terms of Service' })).toHaveAttribute('href', '/terms');
    for (const link of screen.getAllByRole('link', { name: 'Privacy Policy' })) {
      expect(link).toHaveAttribute('href', '/privacy');
    }
  });

  it('keeps Create account disabled until the required box is ticked', async () => {
    renderStep();
    await fillValidForm(); // ticks it
    expect(screen.getByRole('button', { name: /create account/i })).toBeEnabled();

    await userEvent.click(screen.getByLabelText(/I am 18 or older and agree/));
    expect(screen.getByRole('button', { name: /create account/i })).toBeDisabled();
  });

  it('sends the required box as accepted and the AI box as the user left it', async () => {
    vi.mocked(authApi.register).mockReset().mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane Doe', phoneVerified: false },
    } as any);
    renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(authApi.register).toHaveBeenCalled());
    expect(vi.mocked(authApi.register).mock.calls[0][6]).toEqual({ acceptedTerms: true, aiProcessingConsent: false });
  });

  it('sends a yes to AI features only when that box is ticked', async () => {
    vi.mocked(authApi.register).mockReset().mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane Doe', phoneVerified: false },
    } as any);
    renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByLabelText(/Use AI features/));
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(authApi.register).toHaveBeenCalled());
    expect(vi.mocked(authApi.register).mock.calls[0][6]).toEqual({ acceptedTerms: true, aiProcessingConsent: true });
  });

  it('calls onSuccess with phoneVerified on successful registration', async () => {
    vi.mocked(authApi.register).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane Doe', phoneVerified: false },
    } as any);
    const { onSuccess } = renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(false));
  });

  it('calls onAccountExists with the identifier on a 409 instead of navigating away', async () => {
    vi.mocked(authApi.register).mockImplementation(async () => {
      throw Object.assign(new Error('Account already exists.'), {
        response: { status: 409, data: { message: 'Account already exists.' } },
      });
    });
    const { onAccountExists, onSuccess } = renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(onAccountExists).toHaveBeenCalledWith('jane@example.com'));
    expect(onSuccess).not.toHaveBeenCalled();
    expect(screen.queryByRole('link', { name: /continue to login/i })).not.toBeInTheDocument();
  });

  it('does not call onSuccess or onAccountExists when register() fails for a non-409 reason', async () => {
    vi.mocked(authApi.register).mockImplementation(async () => {
      throw Object.assign(new Error('Bad input.'), {
        response: { status: 400, data: { message: 'Bad input.' } },
      });
    });
    const { onAccountExists, onSuccess } = renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(screen.getByText('Bad input.')).toBeInTheDocument());
    expect(onAccountExists).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });

  // Same class of gap as IdentifyStep's own reactivation test: AuthService#loginWithOAuthIdentity
  // reports AUTH_ACCOUNT_DEACTIVATED (with a reactivation token) whenever the Google/Apple email
  // matches an EXISTING account that happens to be deactivated -- reachable here regardless of what
  // the register form's own fields say, since Google's returned email need not match them at all.
  it('shows the reactivation prompt, not a generic error, when Google sign-in reports a deactivated account', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);
    vi.mocked(authApi.google).mockImplementation(async () => {
      throw Object.assign(new Error('This account is deactivated.'), {
        response: { data: { errorCode: 'AUTH_007', details: { reactivationToken: 'reactivate-me-token' } } },
      });
    });
    const { onSuccess, onAccountExists } = renderStep();

    await waitFor(() => expect(initialize).toHaveBeenCalled());
    const { callback } = initialize.mock.calls[0][0];
    callback({ credential: 'a-real-looking-jwt' });

    expect(await screen.findByRole('button', { name: /reactivate my account/i })).toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onAccountExists).not.toHaveBeenCalled();
  });

  // A Google/Apple sign-up here used to drop the ?ref= code the email form sends.
  it("sends the friend's link code along with a Google sign-up", async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);
    vi.mocked(authApi.google).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: false, accountCreated: true },
    } as any);
    const { onSuccess } = renderStep({ referralCode: 'ABCD1234' });

    await waitFor(() => expect(initialize).toHaveBeenCalled());
    const { callback } = initialize.mock.calls[initialize.mock.calls.length - 1][0];
    callback({ credential: 'a-real-looking-jwt' });

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(false));
    // Third argument: the tab's signup attribution (lib/signupAttribution.ts), none in this test.
    expect(authApi.google).toHaveBeenLastCalledWith('a-real-looking-jwt', 'ABCD1234', undefined);
  });
});
