import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { PasswordStep } from './PasswordStep';
import { AuthProvider } from '../../context/AuthContext';
import { authApi } from '../../api/endpoints';
import { AUTH_ACCOUNT_DEACTIVATED } from '../../api/errorCodes';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode } from '../../lib/phoneAuth';

vi.mock('../../api/endpoints', () => ({
  authApi: {
    login: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn(),
    otpEmailRequest: vi.fn(), otpEmailLogin: vi.fn(), otpPhoneLogin: vi.fn(),
  },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

vi.mock('../../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: vi.fn(),
  confirmPhoneVerificationCode: vi.fn(),
  resetPhoneVerification: vi.fn(),
  friendlySendError: vi.fn(() => 'Could not send a verification code right now. Please try again.'),
}));

function renderStep(props: Partial<Parameters<typeof PasswordStep>[0]> = {}) {
  const onSuccess = vi.fn();
  const onNotYou = vi.fn();
  render(
    <MemoryRouter>
      <AuthProvider>
        <PasswordStep identifier="jane@example.com" banner={null} onSuccess={onSuccess} onNotYou={onNotYou} {...props} />
      </AuthProvider>
    </MemoryRouter>
  );
  return { onSuccess, onNotYou };
}

describe('PasswordStep', () => {
  it('prefills the identifier field from props', () => {
    renderStep();
    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('jane@example.com');
  });

  it('shows the banner prop when present', () => {
    renderStep({ banner: 'Password updated successfully. Please sign in using your new password.' });
    expect(screen.getByText('Password updated successfully. Please sign in using your new password.')).toBeInTheDocument();
  });

  it('calls onSuccess with phoneVerified on a successful login', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
    } as any);
    const { onSuccess } = renderStep();

    await userEvent.type(screen.getByLabelText('Password'), 'correct-password-1');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(true));
  });

  it('does NOT call onSuccess when login() rejects -- forcing the password step to render is not authentication', async () => {
    vi.mocked(authApi.login).mockImplementation(async () => {
      throw Object.assign(new Error('Invalid credentials.'), {
        response: { data: { message: 'Invalid credentials.' } },
      });
    });
    const { onSuccess } = renderStep();

    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('Invalid credentials.')).toBeInTheDocument());
    expect(onSuccess).not.toHaveBeenCalled();
  });

  // PasswordStep's identifier field is editable (see its own doc comment -- "lets the user
  // correct a mistyped identifier without going all the way back to IDENTIFY"), so it needs the
  // same format gate IdentifyStep does: editing it into something that's neither a real email nor
  // a real phone number should be caught here too, not sent straight to /auth/login.
  it('shows an error and does not call login() when the identifier is edited into something that looks like neither an email nor a phone number', async () => {
    vi.mocked(authApi.login).mockClear(); // clean call count for the assertion below
    const { onSuccess } = renderStep();
    const identifierField = screen.getByLabelText('Email or mobile number');

    await userEvent.clear(identifierField);
    await userEvent.type(identifierField, '123@');
    await userEvent.type(screen.getByLabelText('Password'), 'some-password');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('Enter a valid email address or 10-digit mobile number.')).toBeInTheDocument();
    expect(authApi.login).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('calls onNotYou when "Not you?" is clicked', async () => {
    const { onNotYou } = renderStep();
    await userEvent.click(screen.getByRole('button', { name: /not you/i }));
    expect(onNotYou).toHaveBeenCalled();
  });

  // Otherwise a click on "Not you?" while login() is still in flight fires the parent's
  // handleNotYou -- which unmounts this step and clears identifier/banner state, explicitly to
  // protect a shared computer (see AuthEntry.tsx's own doc comment on handleNotYou) -- while the
  // stale login() call keeps running in its own closure regardless of the unmount. If it then
  // succeeds, onSuccess still fires and navigates the browser into the authenticated app under
  // the credentials just typed, defeating the exact "not me, back out" the button promises.
  it('disables "Not you?" while login() is in flight', async () => {
    let resolveLogin!: (v: unknown) => void;
    vi.mocked(authApi.login).mockReturnValue(
      new Promise((resolve) => { resolveLogin = resolve; }) as never
    );
    renderStep();

    await userEvent.type(screen.getByLabelText('Password'), 'correct-password-1');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(screen.getByRole('button', { name: /not you/i })).toBeDisabled();

    resolveLogin({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
    });
    await waitFor(() => expect(screen.getByRole('button', { name: /not you/i })).not.toBeDisabled());
  });

  it('shows an OTP toggle and switches the password field for a code field', async () => {
    renderStep();
    await userEvent.click(screen.getByRole('button', { name: /login with otp/i }));
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
  });

  it('email OTP: requesting a code then entering it signs the user in', async () => {
    vi.mocked(authApi.otpEmailRequest).mockResolvedValue({ message: 'sent', devCode: null });
    vi.mocked(authApi.otpEmailLogin).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
    } as any);
    const { onSuccess } = renderStep({ identifier: 'jane@example.com' });

    await userEvent.click(screen.getByRole('button', { name: /login with otp/i }));
    await userEvent.click(screen.getByRole('button', { name: /send code/i }));
    await waitFor(() => expect(authApi.otpEmailRequest).toHaveBeenCalledWith('jane@example.com'));
    await userEvent.type(screen.getByLabelText(/code/i), '482913');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(true));
    expect(authApi.otpEmailLogin).toHaveBeenCalledWith('jane@example.com', '482913');
  });

  // Found by hand-testing: a mistyped email code must say so and leave the code step open for
  // another try (up to the backend's 5 attempts), not end the session or clear the form.
  it('email OTP: a wrong code shows an error, keeps the code step open, and a corrected code then signs in', async () => {
    vi.mocked(authApi.otpEmailRequest).mockResolvedValue({ message: 'sent', devCode: null });
    vi.mocked(authApi.otpEmailLogin)
      .mockRejectedValueOnce(Object.assign(new Error('Request failed'), {
        response: { status: 401, data: { errorCode: 'AUTH_013', message: 'That code is invalid or has expired.' } },
      }))
      .mockResolvedValueOnce({
        data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
      } as any);
    const { onSuccess } = renderStep({ identifier: 'jane@example.com' });

    await userEvent.click(screen.getByRole('button', { name: /login with otp/i }));
    await userEvent.click(screen.getByRole('button', { name: /send code/i }));
    await userEvent.type(await screen.findByLabelText(/code/i), '765689');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));

    expect(await screen.findByText('That code is invalid or has expired.')).toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
    // Still on the code step, and the field is still there to retype into.
    const codeField = screen.getByLabelText(/code/i);
    await userEvent.clear(codeField);
    await userEvent.type(codeField, '765678');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(true));
    expect(authApi.otpEmailLogin).toHaveBeenLastCalledWith('jane@example.com', '765678');
  });

  it('phone OTP: a wrong SMS code says it does not match instead of a generic failure, and the code step stays open', async () => {
    vi.mocked(sendPhoneVerificationCode).mockResolvedValue({} as any);
    vi.mocked(confirmPhoneVerificationCode).mockRejectedValue(
      Object.assign(new Error('bad code'), { code: 'auth/invalid-verification-code' })
    );
    const { onSuccess } = renderStep({ identifier: '9876543210' }); // synthetic-ok: invented placeholder number

    await userEvent.click(screen.getByRole('button', { name: /login with otp/i }));
    await userEvent.click(screen.getByRole('button', { name: /send code/i }));
    await userEvent.type(await screen.findByLabelText(/code/i), '123456');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));

    expect(await screen.findByText("That code doesn't match — check and try again.")).toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(authApi.otpPhoneLogin).not.toHaveBeenCalled();
    expect(screen.getByLabelText(/code/i)).toBeInTheDocument();
  });

  // The request step answers an unknown/mistyped email with the same generic code a wrong code gets
  // ("That code is invalid or has expired."), which reads as nonsense when no code was ever sent.
  it('email OTP: an unknown email at the send step says a code could not be sent, not that a code is invalid', async () => {
    vi.mocked(authApi.otpEmailRequest).mockRejectedValueOnce(Object.assign(new Error('Request failed'), {
      response: { status: 401, data: { errorCode: 'AUTH_013', message: 'That code is invalid or has expired.' } },
    }));
    renderStep({ identifier: 'nobody@example.com' });

    await userEvent.click(screen.getByRole('button', { name: /login with otp/i }));
    await userEvent.click(screen.getByRole('button', { name: /send code/i }));

    expect(await screen.findByText("We couldn't send a code to that address. Check the email and try again.")).toBeInTheDocument();
    expect(screen.queryByText('That code is invalid or has expired.')).not.toBeInTheDocument();
    // Still on the send step, so the address can be corrected and sent again.
    expect(screen.getByRole('button', { name: /send code/i })).toBeInTheDocument();
  });

  // Regression: a correct OTP still runs into enforceAccountIsSignable on a deactivated account
  // (same as the password path) -- this used to fall into the generic "invalid or expired code"
  // branch with no way forward. It must show the same reactivation prompt password login does.
  it('shows the reactivation prompt when email OTP verify reports AUTH_ACCOUNT_DEACTIVATED', async () => {
    vi.mocked(authApi.otpEmailRequest).mockResolvedValue({ message: 'sent', devCode: null });
    vi.mocked(authApi.otpEmailLogin).mockRejectedValue(
      Object.assign(new Error('Request failed'), {
        response: {
          status: 403,
          data: {
            errorCode: AUTH_ACCOUNT_DEACTIVATED,
            message: 'This account is deactivated.',
            details: { reactivationToken: 'reactivation-token' },
          },
        },
      })
    );
    const { onSuccess } = renderStep({ identifier: 'jane@example.com' });

    await userEvent.click(screen.getByRole('button', { name: /login with otp/i }));
    await userEvent.click(screen.getByRole('button', { name: /send code/i }));
    await waitFor(() => expect(authApi.otpEmailRequest).toHaveBeenCalledWith('jane@example.com'));
    await userEvent.type(screen.getByLabelText(/code/i), '482913');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));

    expect(await screen.findByText('Welcome back')).toBeInTheDocument();
    expect(screen.getByText('Reactivate my account')).toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('shows the Terms and Privacy notice beside the Google and Apple buttons', () => {
    renderStep();

    expect(screen.getByText(/continuing with google or apple creates your account/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Terms of Service' })).toHaveAttribute('href', '/terms');
  });
});
