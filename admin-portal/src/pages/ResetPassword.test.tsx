import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import ResetPassword from './ResetPassword';
import { authApi } from '../api/endpoints';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode, resetPhoneVerification } from '../lib/phoneAuth';

vi.mock('../api/endpoints', () => ({
  authApi: { verifyResetPasswordPhone: vi.fn(), resetPassword: vi.fn() },
}));

vi.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: vi.fn(),
  confirmPhoneVerificationCode: vi.fn(),
  resetPhoneVerification: vi.fn(),
}));

vi.mock('../lib/monitoring', () => ({ reportHandledError: vi.fn() }));

const FAKE_CONFIRMATION = { confirm: vi.fn() } as any;

// Invented, fake sequential digits, same as frontend/'s ResetPassword.test.tsx. Declared once so
// the hygiene marker sits in one place rather than on every line that mentions it.
const LOCAL_PHONE = '9876543210'; // synthetic-ok: invented test number
const FULL_PHONE = `+91${LOCAL_PHONE}`;

function LoginStub() {
  const location = useLocation();
  const state = location.state as { message?: string } | null;
  return (
    <div>
      <p>Login page</p>
      <p>message={state?.message ?? 'none'}</p>
    </div>
  );
}

function renderPage(token: string | null = 'reset-token-abc') {
  const path = token ? `/reset-password?token=${token}` : '/reset-password';
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/reset-password" element={<ResetPassword />} />
        <Route path="/login" element={<LoginStub />} />
      </Routes>
    </MemoryRouter>
  );
}

async function confirmPhoneStep(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/mobile number/i), LOCAL_PHONE);
  await user.click(screen.getByRole('button', { name: /continue/i }));
  await screen.findByLabelText(/verification code/i);
}

async function fillCodeAndPassword(user: ReturnType<typeof userEvent.setup>, password: string) {
  await user.type(screen.getByLabelText(/verification code/i), '654321');
  await user.type(screen.getByLabelText(/^new password$/i), password);
  await user.type(screen.getByLabelText(/confirm password/i), password);
}

describe('admin ResetPassword', () => {
  beforeEach(() => {
    vi.mocked(authApi.verifyResetPasswordPhone).mockReset().mockResolvedValue({ message: 'Phone number confirmed.' });
    vi.mocked(authApi.resetPassword).mockReset().mockResolvedValue({ message: 'Password updated.' });
    vi.mocked(sendPhoneVerificationCode).mockReset().mockResolvedValue(FAKE_CONFIRMATION);
    vi.mocked(confirmPhoneVerificationCode).mockReset().mockResolvedValue('fake-firebase-id-token');
    vi.mocked(resetPhoneVerification).mockReset();
  });

  it('shows an error and no phone form when the link has no token', () => {
    renderPage(null);

    expect(screen.getByText(/no reset token found/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/mobile number/i)).not.toBeInTheDocument();
  });

  /**
   * The page used to call the server with the token alone as soon as it opened, expecting the
   * account's phone number back. The server stopped answering that (BH-015), so the page could
   * never send a code. Nothing is asked of the server now until the admin has typed a number.
   */
  it('starts on the phone-number step and asks the server nothing until a number is typed', () => {
    renderPage();

    expect(screen.getByText('Confirm your phone number')).toBeInTheDocument();
    expect(screen.queryByLabelText(/verification code/i)).not.toBeInTheDocument();
    expect(authApi.verifyResetPasswordPhone).not.toHaveBeenCalled();
    expect(sendPhoneVerificationCode).not.toHaveBeenCalled();
  });

  it('keeps Continue disabled until a valid 10-digit number is typed', async () => {
    renderPage();
    const user = userEvent.setup();

    expect(screen.getByRole('button', { name: /continue/i })).toBeDisabled();
    await user.type(screen.getByLabelText(/mobile number/i), '12345');
    expect(screen.getByRole('button', { name: /continue/i })).toBeDisabled();
    await user.clear(screen.getByLabelText(/mobile number/i));
    await user.type(screen.getByLabelText(/mobile number/i), LOCAL_PHONE);
    expect(screen.getByRole('button', { name: /continue/i })).toBeEnabled();
  });

  it('keeps only digits and stops at ten when a number is pasted with spaces and a country code', async () => {
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/mobile number/i), '98765 43210 99');

    expect(screen.getByLabelText(/mobile number/i)).toHaveValue(LOCAL_PHONE);
  });

  it('sends the typed number with the token to the server, and the same number to Firebase', async () => {
    renderPage();
    const user = userEvent.setup();

    await confirmPhoneStep(user);

    expect(authApi.verifyResetPasswordPhone).toHaveBeenCalledWith('reset-token-abc', FULL_PHONE);
    expect(sendPhoneVerificationCode).toHaveBeenCalledWith(FULL_PHONE, 'reset-password-recaptcha');
  });

  it('shows the server message and stays on the phone step when the number does not match the account', async () => {
    vi.mocked(authApi.verifyResetPasswordPhone).mockRejectedValue({
      response: { data: { message: "That doesn't match the phone number on this account." } },
    });
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/mobile number/i), LOCAL_PHONE);
    await user.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(screen.getByText("That doesn't match the phone number on this account.")).toBeInTheDocument());
    expect(screen.queryByLabelText(/verification code/i)).not.toBeInTheDocument();
    expect(sendPhoneVerificationCode).not.toHaveBeenCalled();
  });

  it('shows a plain message when the server cannot be reached at the phone step', async () => {
    vi.mocked(authApi.verifyResetPasswordPhone).mockRejectedValue(new Error('Network Error'));
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/mobile number/i), LOCAL_PHONE);
    await user.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(screen.getByText(/could not verify that phone number/i)).toBeInTheDocument());
    expect(sendPhoneVerificationCode).not.toHaveBeenCalled();
  });

  it('moves to the code and new-password step once the number is confirmed', async () => {
    renderPage();
    const user = userEvent.setup();

    await confirmPhoneStep(user);

    expect(screen.getByText('Set a new password')).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`enter the 6-digit code sent to \\${FULL_PHONE}`, 'i'))).toBeInTheDocument();
  });

  /**
   * The number is right but the text message could not be sent. The admin must be left somewhere
   * they can try again from, with a fresh reCAPTCHA, since a used one throws when reused.
   */
  it('says the code could not be sent, and sends one when "Resend code" is pressed', async () => {
    vi.mocked(sendPhoneVerificationCode).mockReset()
      .mockRejectedValueOnce({ code: 'auth/too-many-requests' })
      .mockResolvedValue(FAKE_CONFIRMATION);
    renderPage();
    const user = userEvent.setup();

    await confirmPhoneStep(user);

    expect(screen.getByText(/could not send a verification code/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/verification code/i)).toBeDisabled();
    expect(resetPhoneVerification).toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: /resend code/i }));

    await waitFor(() => expect(screen.getByLabelText(/verification code/i)).toBeEnabled());
    expect(screen.queryByText(/could not send a verification code/i)).not.toBeInTheDocument();
  });

  it('resends the code through Firebase without asking the server about the number again', async () => {
    renderPage();
    const user = userEvent.setup();
    await confirmPhoneStep(user);
    vi.mocked(authApi.verifyResetPasswordPhone).mockClear();
    vi.mocked(sendPhoneVerificationCode).mockClear();

    await user.click(screen.getByRole('button', { name: /resend code/i }));

    expect(authApi.verifyResetPasswordPhone).not.toHaveBeenCalled();
    expect(sendPhoneVerificationCode).toHaveBeenCalledWith(FULL_PHONE, 'reset-password-recaptcha');
  });

  // A resend while the reset is still being sent would clear the code under a submission that may
  // yet succeed and replace the screen the admin is looking at.
  it('disables "Resend code" while the reset is being sent', async () => {
    let resolveReset!: (v: { message: string }) => void;
    vi.mocked(authApi.resetPassword).mockReset().mockReturnValue(
      new Promise((resolve) => { resolveReset = resolve; }) as never
    );
    renderPage();
    const user = userEvent.setup();
    await confirmPhoneStep(user);

    await fillCodeAndPassword(user, 'kettle#Orbit42river');
    await user.click(screen.getByRole('button', { name: /update password/i }));

    expect(screen.getByRole('button', { name: /resend code/i })).toBeDisabled();

    resolveReset({ message: 'Password updated.' });
    await waitFor(() => expect(screen.getByText('Password updated')).toBeInTheDocument());
  });

  it('keeps "Update password" disabled until the code is six digits and the passwords match', async () => {
    renderPage();
    const user = userEvent.setup();
    await confirmPhoneStep(user);

    await user.type(screen.getByLabelText(/verification code/i), '65432');
    await user.type(screen.getByLabelText(/^new password$/i), 'kettle#Orbit42river');
    await user.type(screen.getByLabelText(/confirm password/i), 'kettle#Orbit42rive');
    expect(screen.getByRole('button', { name: /update password/i })).toBeDisabled();

    await user.type(screen.getByLabelText(/verification code/i), '1');
    expect(screen.getByRole('button', { name: /update password/i })).toBeDisabled();

    await user.type(screen.getByLabelText(/confirm password/i), 'r');
    expect(screen.getByRole('button', { name: /update password/i })).toBeEnabled();
  });

  it('completes the reset end to end: number, code, new password', async () => {
    renderPage();
    const user = userEvent.setup();
    await confirmPhoneStep(user);

    await fillCodeAndPassword(user, 'kettle#Orbit42river');
    await user.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => expect(screen.getByText('Password updated')).toBeInTheDocument());
    expect(confirmPhoneVerificationCode).toHaveBeenCalledWith(FAKE_CONFIRMATION, '654321');
    expect(authApi.resetPassword).toHaveBeenCalledWith('reset-token-abc', 'fake-firebase-id-token', 'kettle#Orbit42river');
  });

  it('shows a plain message when Firebase refuses the code', async () => {
    vi.mocked(confirmPhoneVerificationCode).mockReset().mockRejectedValue({ code: 'auth/invalid-verification-code' });
    renderPage();
    const user = userEvent.setup();
    await confirmPhoneStep(user);

    await fillCodeAndPassword(user, 'kettle#Orbit42river');
    await user.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => expect(screen.getByText(/that code doesn't match/i)).toBeInTheDocument());
    expect(authApi.resetPassword).not.toHaveBeenCalled();
  });

  it('goes to sign-in with a message after a successful reset', async () => {
    renderPage();
    const user = userEvent.setup();
    await confirmPhoneStep(user);

    await fillCodeAndPassword(user, 'kettle#Orbit42river');
    await user.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => expect(screen.getByText('Login page')).toBeInTheDocument(), { timeout: 3000 });
    expect(screen.getByText('message=Password reset successfully. Please sign in using your new password.')).toBeInTheDocument();
  });

  /**
   * The server can refuse the new password after the phone code was accepted (too common, or the
   * same as the current one). Firebase takes a code once, so the second try must send the proof
   * it already has rather than check the code again.
   */
  it('lets an admin choose another password after the server refuses the first, without checking the code twice', async () => {
    const refusal = 'That password is one of the most commonly used, so it is easy to guess. Choose a different one.';
    vi.mocked(authApi.resetPassword).mockReset()
      .mockRejectedValueOnce({ response: { data: { message: refusal } } })
      .mockResolvedValue({ message: 'Password updated.' });
    renderPage();
    const user = userEvent.setup();
    await confirmPhoneStep(user);

    await fillCodeAndPassword(user, 'password123');
    await user.click(screen.getByRole('button', { name: /update password/i }));
    await waitFor(() => expect(screen.getByText(refusal)).toBeInTheDocument());

    await user.clear(screen.getByLabelText(/^new password$/i));
    await user.type(screen.getByLabelText(/^new password$/i), 'kettle#Orbit42river');
    await user.clear(screen.getByLabelText(/confirm password/i));
    await user.type(screen.getByLabelText(/confirm password/i), 'kettle#Orbit42river');
    await user.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => expect(screen.getByText('Password updated')).toBeInTheDocument());
    expect(confirmPhoneVerificationCode).toHaveBeenCalledTimes(1);
    expect(authApi.resetPassword).toHaveBeenLastCalledWith('reset-token-abc', 'fake-firebase-id-token', 'kettle#Orbit42river');
  });

  it('checks the new code after a resend, not the proof of the old one', async () => {
    vi.mocked(authApi.resetPassword).mockReset()
      .mockRejectedValueOnce({ response: { data: { message: 'Refused.' } } })
      .mockResolvedValue({ message: 'Password updated.' });
    vi.mocked(confirmPhoneVerificationCode).mockReset()
      .mockResolvedValueOnce('first-id-token')
      .mockResolvedValueOnce('second-id-token');
    renderPage();
    const user = userEvent.setup();
    await confirmPhoneStep(user);

    await fillCodeAndPassword(user, 'kettle#Orbit42river');
    await user.click(screen.getByRole('button', { name: /update password/i }));
    await waitFor(() => expect(screen.getByText('Refused.')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /resend code/i }));
    await waitFor(() => expect(sendPhoneVerificationCode).toHaveBeenCalledTimes(2));
    await user.type(screen.getByLabelText(/verification code/i), '112233');
    await user.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => expect(screen.getByText('Password updated')).toBeInTheDocument());
    expect(authApi.resetPassword).toHaveBeenLastCalledWith('reset-token-abc', 'second-id-token', 'kettle#Orbit42river');
  });

  it('has exactly one reCAPTCHA anchor in the page, before and after the step changes', async () => {
    const { container } = renderPage();
    const user = userEvent.setup();

    expect(container.querySelectorAll('#reset-password-recaptcha')).toHaveLength(1);

    await confirmPhoneStep(user);

    expect(container.querySelectorAll('#reset-password-recaptcha')).toHaveLength(1);
  });

  it('clears the reCAPTCHA when the page is left', () => {
    const { unmount } = renderPage();

    unmount();

    expect(resetPhoneVerification).toHaveBeenCalled();
  });
});
